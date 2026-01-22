package org.jens.mp4stream;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

import org.micromanager.LogManager;
import org.micromanager.PropertyMap;
import org.micromanager.Studio;
import org.micromanager.data.Image;
import org.micromanager.data.Metadata;
import org.micromanager.data.Processor;
import org.micromanager.data.ProcessorContext;
import org.micromanager.data.SummaryMetadata;
import org.micromanager.display.ChannelDisplaySettings;
import org.micromanager.display.ComponentDisplaySettings;
import org.micromanager.display.DisplaySettings;
import org.micromanager.display.DisplayWindow;
import org.micromanager.events.LiveModeEvent;
import org.micromanager.acquisition.AcquisitionEndedEvent;

import com.google.common.eventbus.Subscribe;


public final class MP4StreamProcessor implements Processor {

   private static final Preferences PREFS =
         Preferences.userNodeForPackage(MP4StreamConfigurator.class);

   private final Studio studio_;
   private final PropertyMap settings_;

   public MP4StreamProcessor(Studio studio, PropertyMap settings) {
      studio_ = studio;
      settings_ = settings;
   }

   // Reused per-dimension
   private int width_ = -1;
   private int height_ = -1;
   private byte[] plane8_ = null;
   private BufferedImage grayImg_ = null;
   private Graphics2D g2d_ = null;

   // FFmpeg session state
   private final Object ffLock_ = new Object();
   private volatile FfmpegSession ff_ = null;
   private int segmentIndex_ = 0;

   // Recording mode (loaded from settings)
   private String recordingMode_ = MP4StreamConfigurator.MODE_CONSTANT_FPS;
   private double targetFps_ = MP4StreamConfigurator.DEFAULT_TARGET_FPS;
   private double timelapseFactor_ = MP4StreamConfigurator.DEFAULT_TIMELAPSE_FACTOR;

   // CFR (Constant Frame Rate) output state
   private long nextOutFrameIndex_ = 0;
   private boolean haveLastFrame_ = false;
   private byte[] lastFrame8_ = null;

   // VFR (Variable Frame Rate / Realtime) frame counter
   private long vfrFrameCount_ = 0;

   // Timing for Δt overlay
   private boolean t0IsElapsedMs_ = false;
   private double t0ElapsedMs_ = 0.0;

   private boolean t0IsReceivedTime_ = false;
   private long t0ReceivedNs_ = 0L;

   private long t0WallNanos_ = 0L;

   private static final boolean USE_LIVE_DISPLAY_SCALING = true;

   private static final String LOG_PREFIX = "[MP4Stream] ";

   // Watchdog tuning: timeout = max(WD_MIN_MS, WD_MULT*exposure + WD_MARGIN_MS)
   private static final double WD_MIN_MS = 1500;     // floor
   private static final double WD_MARGIN_MS = 1000;  // overhead cushion
   private static final double WD_MULT = 2.0;        // tolerate up to 2 frame periods

   // Shared across threads
   private volatile long watchdogTimeoutNanos_ = (long) (WD_MIN_MS * 1e6);
   private volatile long lastFrameNanos_ = 0L; // updated after each encoded frame
   private volatile Thread watchdog_ = null;
   private volatile boolean watchdogRun_ = false;

   // Event listener registration tracking
   private volatile boolean eventsRegistered_ = false;

   // Rate limiting (avoid heavy core calls / log spam)
   private volatile long lastWdUpdateNanos_ = 0L;
   private volatile long lastExpUnavailableLogNanos_ = 0L;
   private static final long WD_REFRESH_PERIOD_NANOS = 1_000_000_000L; // 1s
   private static final long EXP_UNAVAILABLE_LOG_PERIOD_NANOS = 5_000_000_000L; // 5s

   // Track display scaling for change detection
   private DisplayScaling lastScaling_ = null;
   private volatile long lastScalingLogNanos_ = 0L;
   private static final long SCALING_LOG_PERIOD_NANOS = 2_000_000_000L; // 2s
   private static final double SCALING_CHANGE_THRESHOLD_PCT = 5.0; // Only log if range changes by >5%

   private LogManager logs() {
      return (studio_ == null) ? null : studio_.logs();
   }

   private void logInfo_(String msg) {
      LogManager lm = logs();
      if (lm != null) {
         lm.logMessage(LOG_PREFIX + msg);
      }
   }

   private void logDebug_(String msg) {
      LogManager lm = logs();
      if (lm != null) {
         lm.logDebugMessage(LOG_PREFIX + msg);
      }
   }

   private void logWarn_(String msg) {
      LogManager lm = logs();
      if (lm != null) {
         // No logWarning() in this MM version; keep it as a message with a WARN tag.
         lm.logMessage(LOG_PREFIX + "WARN: " + msg);
      }
   }

   private void logError_(String msg, Exception e) {
      LogManager lm = logs();
      if (lm != null) {
         lm.logError(e, LOG_PREFIX + msg);
      }
   }

   // Helper to safely get metadata from image
   private static Metadata getMetadata(Image img) {
      try {
         return img.getMetadata();
      } catch (Exception ignored) {
         return null;
      }
   }

   // Helper to clamp negative values to zero
   private static double clampNonNegative(double value) {
      return (value < 0) ? 0 : value;
   }

   private static long clampNonNegative(long value) {
      return (value < 0) ? 0 : value;
   }

   private static final class DisplayScaling {
      final long min;
      final long max;
      final double gamma;

      DisplayScaling(long min, long max, double gamma) {
         this.min = min;
         this.max = max;
         this.gamma = gamma;
      }

      boolean equals(DisplayScaling other) {
         if (other == null) {
            return false;
         }
         return this.min == other.min && this.max == other.max && 
                Math.abs(this.gamma - other.gamma) < 1e-9;
      }
   }

   private void logScalingChangeIfNeeded(DisplayScaling newScaling) {
      if (newScaling == null) {
         return;
      }

      // Check if scaling actually changed
      if (lastScaling_ != null && lastScaling_.equals(newScaling)) {
         return;
      }

      // Rate limit logging
      long now = System.nanoTime();
      if (lastScalingLogNanos_ != 0L && (now - lastScalingLogNanos_) < SCALING_LOG_PERIOD_NANOS) {
         // Still update tracking even if not logging
         lastScaling_ = newScaling;
         return;
      }

      // Check if change is significant enough to log
      long newRange = newScaling.max - newScaling.min;
      if (lastScaling_ != null) {
         long oldRange = lastScaling_.max - lastScaling_.min;
         
         if (oldRange > 0) {
            double changePct = Math.abs((double)(newRange - oldRange) / oldRange * 100.0);
            if (changePct < SCALING_CHANGE_THRESHOLD_PCT) {
               // Change is too small, skip logging but update tracking
               lastScaling_ = newScaling;
               return;
            }
         }
      }

      lastScalingLogNanos_ = now;

      // Log the change with useful info including previous values
      if (lastScaling_ != null) {
         long oldRange = lastScaling_.max - lastScaling_.min;
         double changePct = oldRange > 0 ? 
            ((double)(newRange - oldRange) / oldRange * 100.0) : 0.0;
         logDebug_(String.format(java.util.Locale.US,
               "Display scaling changed: min=%d→%d, max=%d→%d, range=%d→%d (%.1f%%), gamma=%.3f",
               lastScaling_.min, newScaling.min,
               lastScaling_.max, newScaling.max,
               oldRange, newRange, changePct, newScaling.gamma));
      } else {
         logDebug_(String.format(java.util.Locale.US,
               "Display scaling: min=%d, max=%d, range=%d, gamma=%.3f",
               newScaling.min, newScaling.max, newRange, newScaling.gamma));
      }

      lastScaling_ = newScaling;
   }

   private DisplayScaling getLiveDisplayScaling(Image img) {
      // Fallback: full dynamic range
      long fallbackMin = 0;
      long fallbackMax = (img.getBytesPerPixel() == 2) ? 65535 : 255;
      double fallbackGamma = 1.0;

      if (studio_ == null) {
         return new DisplayScaling(fallbackMin, fallbackMax, fallbackGamma);
      }

      try {
         DisplayWindow win = studio_.live().getDisplay();
         if (win == null) {
            return new DisplayScaling(fallbackMin, fallbackMax, fallbackGamma);
         }

         DisplaySettings ds = win.getDisplaySettings();
         int ch = 0;
         try {
            ch = img.getCoords().getChannel();
         } catch (Exception ignored) {}

         if (ch < 0 || ch >= ds.getNumberOfChannels()) {
            ch = 0;
         }

         ChannelDisplaySettings cds = ds.getChannelSettings(ch);
         ComponentDisplaySettings c0 = cds.getComponentSettings(0);

         long min = c0.getScalingMinimum();
         long max = c0.getScalingMaximum();
         double gamma = c0.getScalingGamma();

         if (max <= min || !(gamma > 0.0)) {
            return new DisplayScaling(fallbackMin, fallbackMax, fallbackGamma);
         }

         return new DisplayScaling(min, max, gamma);

      } catch (Exception e) {
         return new DisplayScaling(fallbackMin, fallbackMax, fallbackGamma);
      }
   }

   @Override
   public SummaryMetadata processSummaryMetadata(SummaryMetadata summary) {
      return summary;
   }

   @Override
   public void processImage(Image img, ProcessorContext context) {
      try {
         // Always forward image downstream, regardless of recorder failures.
         recordFrameIfConfigured(img);
      } catch (Exception e) {
         logError_("Processor exception while handling frame.", e);
      } finally {
         context.outputImage(img);
      }
   }

   @Override
   public void cleanup(ProcessorContext context) {
      // Stop watchdog first, then close ffmpeg.
      stopWatchdog();
      stopFfmpeg();
      disposeOverlay();
   }

   // --- Event handlers for immediate finalization ---

   @Subscribe
   public void onLiveModeEvent(LiveModeEvent event) {
      if (!event.isOn() && ff_ != null) {
         logInfo_("Live mode stopped - finalizing MP4 immediately.");
         stopFfmpeg();
      }
   }

   @Subscribe
   public void onAcquisitionEnded(AcquisitionEndedEvent event) {
      if (ff_ != null) {
         logInfo_("Acquisition ended - finalizing MP4 immediately.");
         stopFfmpeg();
      }
   }

   private void registerForEvents() {
      if (studio_ != null && !eventsRegistered_) {
         try {
            studio_.events().registerForEvents(this);
            eventsRegistered_ = true;
            logDebug_("Registered for Live/Acquisition events.");
         } catch (Exception e) {
            logWarn_("Failed to register for events: " + e.getMessage());
         }
      }
   }

   private void unregisterForEvents() {
      if (studio_ != null && eventsRegistered_) {
         try {
            studio_.events().unregisterForEvents(this);
            eventsRegistered_ = false;
            logDebug_("Unregistered from Live/Acquisition events.");
         } catch (Exception e) {
            logWarn_("Failed to unregister from events: " + e.getMessage());
         }
      }
   }

   private void recordFrameIfConfigured(Image img) throws IOException {
      final String outPath = getSettingString(MP4StreamConfigurator.KEY_OUTPUT_PATH, "");
      if (outPath == null || outPath.trim().isEmpty()) {
         // Stop recording if output path is cleared
         if (ff_ != null) {
            stopFfmpeg();
         }
         return;
      }

      // Only record when Live is running or an acquisition (MDA) is running.
      if (!shouldRecordNow()) {
         // Stop recording if acquisition/live stopped
         if (ff_ != null) {
            stopFfmpeg();
         }
         return;
      }

      final int w = img.getWidth();
      final int h = img.getHeight();
      if (w <= 0 || h <= 0) {
         return;
      }

      // Start if needed, restart on dimension change
      if (ff_ == null || w != width_ || h != height_) {
         startFfmpegForDimensions(outPath, w, h, img);
      } else {
         // Update watchdog timeout occasionally while actively recording (exposure can change mid-live).
         updateWatchdogFromExposureRateLimited_();
      }

      ensureBuffersForDimensions(w, h);

      // Convert incoming pixels to gray8
      if (USE_LIVE_DISPLAY_SCALING) {
         DisplayScaling sc = getLiveDisplayScaling(img);
         logScalingChangeIfNeeded(sc);
         convertToGray8WithScaling(img, plane8_, sc.min, sc.max, sc.gamma);
      } else {
         convertToGray8(img, plane8_);
      }

      // Δt overlay: prefer elapsed time, else received time, else wall clock.
      double dtSec = computeDeltaTSeconds(img);

      // Write frame using configured recording mode
      writeFrameWithMode(plane8_, w, h, dtSec);
   }

   private boolean shouldRecordNow() {
      if (studio_ == null) {
         return false;
      }

      try {
         if (studio_.live().isLiveModeOn()) {
            return true;
         }
      } catch (Exception ignored) {}

      try {
         if (studio_.acquisitions().isAcquisitionRunning()) {
            return true;
         }
      } catch (Exception ignored) {}

      return false;
   }

   private double computeDeltaTSeconds(Image img) {
      final Metadata md = getMetadata(img);
   
      // Prefer acquisition elapsed time (use non-deprecated overload)
      if (t0IsElapsedMs_ && md != null && md.hasElapsedTimeMs()) {
         try {
            final double ms = md.getElapsedTimeMs(Double.NaN); // non-deprecated
            if (Double.isFinite(ms)) {
               final double dtMs = clampNonNegative(ms - t0ElapsedMs_);
               return dtMs / 1000.0;
            }
         } catch (Exception ignored) {}
      }
   
      // Fallback: received time
      if (t0IsReceivedTime_ && md != null) {
         try {
            final String rt = md.getReceivedTime();
            if (rt != null && !rt.trim().isEmpty()) {
               final Instant inst = Instant.parse(rt.trim());
               final long ns = inst.getEpochSecond() * 1_000_000_000L + inst.getNano();
               final long dtNs = clampNonNegative(ns - t0ReceivedNs_);
               return dtNs / 1_000_000_000.0;
            }
         } catch (Exception ignored) {}
      }
   
      // Last fallback: wall clock since segment start
      final long dtNs = clampNonNegative(System.nanoTime() - t0WallNanos_);
      return dtNs / 1_000_000_000.0;
   }
   

   private void startFfmpegForDimensions(String baseOutPath, int w, int h, Image firstImg) throws IOException {
      // Close any existing stream
      stopFfmpeg();

      width_ = w;
      height_ = h;
      segmentIndex_++;

      // Load recording mode settings
      recordingMode_ = getSettingString(MP4StreamConfigurator.KEY_RECORDING_MODE, 
            MP4StreamConfigurator.MODE_CONSTANT_FPS);
      targetFps_ = getSettingDouble(MP4StreamConfigurator.KEY_TARGET_FPS, 
            MP4StreamConfigurator.DEFAULT_TARGET_FPS);
      timelapseFactor_ = getSettingDouble(MP4StreamConfigurator.KEY_TIMELAPSE_FACTOR, 
            MP4StreamConfigurator.DEFAULT_TIMELAPSE_FACTOR);

      // MP4 cannot change resolution mid-stream. Segment output to new file.
      final String segPath = makeSegmentPath(baseOutPath, w, h, segmentIndex_);

      final String ffmpegPath = getSettingString(MP4StreamConfigurator.KEY_FFMPEG_PATH, "");
      final String exe = (ffmpegPath == null || ffmpegPath.trim().isEmpty()) ? "ffmpeg" : ffmpegPath;

      // Determine effective output FPS based on mode
      double effectiveFps;
      String modeDescription;
      if (MP4StreamConfigurator.MODE_REALTIME.equals(recordingMode_)) {
         // VFR mode: use reasonable default, actual timing handled via VFR
         effectiveFps = 30.0; // Base rate for VFR container
         modeDescription = "realtime/VFR";
      } else if (MP4StreamConfigurator.MODE_TIMELAPSE.equals(recordingMode_)) {
         // Timelapse: output at target FPS, but time is compressed
         effectiveFps = targetFps_;
         modeDescription = String.format(java.util.Locale.US, "timelapse %.1fx @%.1f fps", 
               timelapseFactor_, targetFps_);
      } else {
         // Constant FPS (default)
         effectiveFps = targetFps_;
         modeDescription = String.format(java.util.Locale.US, "constant @%.1f fps", targetFps_);
      }

      // Build FFmpeg command as cmd-list
      List<String> cmd = new ArrayList<>();
      cmd.add(exe);
      cmd.add("-y"); // overwrite existing file
      cmd.add("-f"); cmd.add("rawvideo"); // input format
      cmd.add("-pix_fmt"); cmd.add("gray"); // pixel format
      cmd.add("-s"); cmd.add(w + "x" + h); // size

      String fpsStr = String.format(java.util.Locale.US, "%.3f", effectiveFps);
      cmd.add("-r"); cmd.add(fpsStr); // frame rate
      cmd.add("-i"); cmd.add("-"); // input from stdin

      // video encoding (CPU-only)
      cmd.add("-an"); // no audio
      cmd.add("-c:v"); cmd.add("libx264"); // video codec
      cmd.add("-preset"); cmd.add("veryfast"); // preset
      cmd.add("-crf"); cmd.add("18"); // constant rate factor
      cmd.add("-pix_fmt"); cmd.add("yuv420p"); // output pixel format

      cmd.add(segPath); // output file name

      logInfo_("Starting FFmpeg: " + segPath + " (" + w + "x" + h + ", " + modeDescription + ")");
      logDebug_("FFmpeg command: " + cmd);

      synchronized (ffLock_) {
         ff_ = new FfmpegSession(cmd);
      }

      initTimeZero(firstImg);
      nextOutFrameIndex_ = 0;
      haveLastFrame_ = false;
      lastFrame8_ = null;
      vfrFrameCount_ = 0;

      // Reset scaling tracking for new segment (will log on first frame)
      lastScaling_ = null;

      // Recreate overlay resources for this dimension
      disposeOverlay();
      ensureBuffersForDimensions(w, h);

      // Arm watchdog baseline and timeout
      lastFrameNanos_ = System.nanoTime();
      updateWatchdogFromExposureRateLimited_(); // immediate update on start
      startWatchdog();

      // Register for Live/Acquisition events to finalize immediately when they stop
      registerForEvents();
   }

   private void initTimeZero(Image img) {
      t0IsElapsedMs_ = false;
      t0IsReceivedTime_ = false;
      t0ElapsedMs_ = 0.0;
      t0ReceivedNs_ = 0L;
      t0WallNanos_ = System.nanoTime();
   
      final Metadata md = getMetadata(img);
      if (md == null) {
         return;
      }
   
      // Prefer elapsed time (acquisition timeline). Use non-deprecated overload.
      try {
         if (md.hasElapsedTimeMs()) {
            final double ms = md.getElapsedTimeMs(Double.NaN); // non-deprecated
            if (Double.isFinite(ms)) {
               t0ElapsedMs_ = ms;
               t0IsElapsedMs_ = true;
            }
         }
      } catch (Exception ignored) {}
   
      // Also latch received time baseline if present
      try {
         final String rt = md.getReceivedTime();
         if (rt != null && !rt.trim().isEmpty()) {
            final Instant inst = Instant.parse(rt.trim());
            t0ReceivedNs_ = inst.getEpochSecond() * 1_000_000_000L + inst.getNano();
            t0IsReceivedTime_ = true;
         }
      } catch (Exception ignored) {}
   }
   

   private void writeFrameWithMode(byte[] frame8, int w, int h, double dtSec) throws IOException {
      // Update activity marker for watchdog BEFORE write attempt
      lastFrameNanos_ = System.nanoTime();

      synchronized (ffLock_) {
         if (ff_ == null) {
            return;
         }

         if (MP4StreamConfigurator.MODE_REALTIME.equals(recordingMode_)) {
            // VFR mode: write every frame exactly once
            overlayDeltaT(frame8, w, h, dtSec);
            ff_.writeFrame(frame8);
            vfrFrameCount_++;
         } else if (MP4StreamConfigurator.MODE_TIMELAPSE.equals(recordingMode_)) {
            // Timelapse mode: compress time by factor, then use CFR logic
            double compressedDtSec = dtSec * timelapseFactor_;
            writeCfrFrame(frame8, w, h, dtSec, compressedDtSec);
         } else {
            // Constant FPS mode (default): CFR with real time
            writeCfrFrame(frame8, w, h, dtSec, dtSec);
         }
      }
   }

   // CFR (Constant Frame Rate) helper - duplicates/drops frames to match target FPS
   private void writeCfrFrame(byte[] frame8, int w, int h, double overlayDtSec, double framingDtSec) 
         throws IOException {
      // framingDtSec determines which frame index this belongs to
      // overlayDtSec is displayed in the overlay (can differ in timelapse mode)
      long targetIndex = (long) Math.floor((framingDtSec * targetFps_) + 1e-9);

      if (lastFrame8_ == null || lastFrame8_.length != frame8.length) {
         lastFrame8_ = new byte[frame8.length];
         haveLastFrame_ = false;
      }

      // Fill gaps using last frame (CFR)
      if (haveLastFrame_) {
         while (nextOutFrameIndex_ < targetIndex) {
            ff_.writeFrame(lastFrame8_);
            nextOutFrameIndex_++;
         }
      } else {
         nextOutFrameIndex_ = targetIndex;
      }

      // Write at target index
      if (nextOutFrameIndex_ == targetIndex) {
         overlayDeltaT(frame8, w, h, overlayDtSec);
         ff_.writeFrame(frame8);
         nextOutFrameIndex_++;
      }
      
      // Always update last frame (whether written or dropped)
      System.arraycopy(frame8, 0, lastFrame8_, 0, frame8.length);
      haveLastFrame_ = true;
   }

   private static String formatElapsedHhMmSsMmm(double dtSec) {
      if (dtSec < 0) {
         dtSec = 0;
      }
      long totalMs = (long) Math.round(dtSec * 1000.0);

      long ms = totalMs % 1000;
      long totalSec = totalMs / 1000;

      long sec = totalSec % 60;
      long totalMin = totalSec / 60;

      long min = totalMin % 60;
      long hours = totalMin / 60;

      return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d", hours, min, sec, ms);
   }

   private double getCurrentExposureMs_() {
      try {
         return (studio_ == null) ? Double.NaN : studio_.core().getExposure();
      } catch (Exception e) {
         // Do not spam here; caller rate-limits.
         return Double.NaN;
      }
   }

   private void updateWatchdogFromExposureRateLimited_() {
      final long now = System.nanoTime();
      final long last = lastWdUpdateNanos_;
      if (last != 0L && (now - last) < WD_REFRESH_PERIOD_NANOS) {
         return;
      }
      lastWdUpdateNanos_ = now;
      updateWatchdogFromExposure_();
   }

   /**
    * Updates watchdogTimeoutNanos_ from current core exposure.
    * Requirement: every *change* to watchdog timeout is logged in DEBUG.
    */
   private void updateWatchdogFromExposure_() {
      double expMs = getCurrentExposureMs_();
      if (!Double.isFinite(expMs) || expMs <= 0) {
         // Rate-limit exposure-unavailable logs
         long now = System.nanoTime();
         if (lastExpUnavailableLogNanos_ == 0L || (now - lastExpUnavailableLogNanos_) > EXP_UNAVAILABLE_LOG_PERIOD_NANOS) {
            lastExpUnavailableLogNanos_ = now;
            logDebug_("Exposure unavailable; watchdog unchanged.");
         }
         return;
      }

      double wdMs = Math.max(WD_MIN_MS, WD_MULT * expMs + WD_MARGIN_MS);
      long wdNs = (long) (wdMs * 1e6);

      if (wdNs != watchdogTimeoutNanos_) {
         logDebug_(String.format(java.util.Locale.US,
               "Watchdog timeout updated: %.0f ms (exposure %.0f ms, mult %.1f, margin %.0f ms)",
               wdMs, expMs, WD_MULT, WD_MARGIN_MS));
         watchdogTimeoutNanos_ = wdNs;
      }
   }

   private void startWatchdog() {
      if (watchdog_ != null && watchdog_.isAlive()) {
         return;
      }

      watchdogRun_ = true;

      watchdog_ = new Thread(() -> {
         try {
            while (watchdogRun_) {
               try {
                  Thread.sleep(200);
               } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                  return;
               }

               // Only act if recording is active
               if (ff_ == null) {
                  continue;
               }

               // Note: Live/MDA stop detection is now handled by event listeners
               // (onLiveModeEvent, onAcquisitionEnded) for immediate response.
               // Watchdog only handles timeout (no frames received for too long).

               final long last = lastFrameNanos_;
               if (last == 0L) {
                  continue; // not armed
               }

               final long idleNanos = System.nanoTime() - last;

               // Read current timeout (updated elsewhere)
               final long timeoutNanos = watchdogTimeoutNanos_;

               if (idleNanos > timeoutNanos) {
                  // stopFfmpeg() is idempotent.
                  logInfo_("Watchdog timeout - finalizing MP4.");
                  stopFfmpeg();
                  // Disarm to prevent repeated stop attempts before ff_ becomes visible as null everywhere.
                  lastFrameNanos_ = 0L;
               }
            }
         } finally {
            // Allow restart
            watchdog_ = null;
         }
      }, "mp4stream-watchdog");

      watchdog_.setDaemon(true);
      watchdog_.start();
   }

   private void stopWatchdog() {
      watchdogRun_ = false;
      Thread t = watchdog_;
      if (t != null) {
         t.interrupt();
      }
      watchdog_ = null;
   }

   private static String makeSegmentPath(String baseOutPath, int w, int h, int idx) {
      File f = new File(baseOutPath);
      String name = f.getName();
      String parent = f.getParent();
      if (parent == null) {
         parent = ".";
      }

      String stem = name;
      if (stem.toLowerCase().endsWith(".mp4")) {
         stem = stem.substring(0, stem.length() - 4);
      }

      // Find an unused filename to avoid overwriting existing files
      int candidate = idx;
      File candidateFile;
      do {
         String segName = String.format("%s_%dx%d_seg%03d.mp4", stem, w, h, candidate);
         candidateFile = new File(parent, segName);
         candidate++;
      } while (candidateFile.exists() && candidate < 10000);

      return candidateFile.getAbsolutePath();
   }

   private void stopFfmpeg() {
      FfmpegSession toClose = null;

      synchronized (ffLock_) {
         if (ff_ == null) {
            return;
         }
         toClose = ff_;
         ff_ = null;
      }

      // Unregister from events since we're no longer recording
      unregisterForEvents();

      // Close outside lock to avoid blocking producers/watchdog while ffmpeg finalizes.
      long frameCount = MP4StreamConfigurator.MODE_REALTIME.equals(recordingMode_) 
            ? vfrFrameCount_ : nextOutFrameIndex_;
      logDebug_("Stopping FFmpeg and finalizing MP4 file (" + frameCount + " frames)...");
      try {
         toClose.close();
         logInfo_("FFmpeg finalized successfully (" + frameCount + " frames written).");
      } catch (Exception e) {
         logWarn_("FFmpeg close failed (ignored): " + e.getMessage());
      }
   }

   private void ensureBuffersForDimensions(int w, int h) {
      int n = w * h;
      if (plane8_ == null || plane8_.length != n) {
         plane8_ = new byte[n];
      }

      if (grayImg_ == null || grayImg_.getWidth() != w || grayImg_.getHeight() != h) {
         grayImg_ = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);

         g2d_ = grayImg_.createGraphics();
         g2d_.setFont(new Font("SansSerif", Font.BOLD, 18));
         g2d_.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
         g2d_.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      }
   }

   private void disposeOverlay() {
      if (g2d_ != null) {
         try {
            g2d_.dispose();
         } catch (Exception ignored) {}
      }
      g2d_ = null;
      grayImg_ = null;
   }

   private static void convertToGray8(Image img, byte[] out8) {
      final int bpp = img.getBytesPerPixel();
      final Object raw = img.getRawPixelsCopy();

      if (bpp == 1) {
         byte[] in = (byte[]) raw;
         System.arraycopy(in, 0, out8, 0, Math.min(in.length, out8.length));
         return;
      }

      if (bpp == 2) {
         short[] in16 = (short[]) raw;
         int n = Math.min(in16.length, out8.length);
         for (int i = 0; i < n; i++) {
            out8[i] = (byte) ((in16[i] >>> 8) & 0xFF);
         }
         return;
      }

      // Unsupported; leave black
      for (int i = 0; i < out8.length; i++) {
         out8[i] = 0;
      }
   }

   private static void convertToGray8WithScaling(Image img, byte[] out8, long min, long max, double gamma) {
      final int bpp = img.getBytesPerPixel();
      final Object raw = img.getRawPixelsCopy();

      if (max <= min) {
         convertToGray8(img, out8);
         return;
      }
      if (!(gamma > 0.0)) {
         gamma = 1.0;
      }

      final double invRange = 1.0 / (double) (max - min);
      final boolean useGamma = (gamma != 1.0);

      if (bpp == 1) {
         byte[] in = (byte[]) raw;
         int n = Math.min(in.length, out8.length);
         for (int i = 0; i < n; i++) {
            int v = in[i] & 0xFF;
            double x = Math.max(0.0, Math.min(1.0, (v - (double) min) * invRange));
            if (useGamma) {
               x = Math.pow(x, gamma);
            }
            out8[i] = (byte) (int) Math.round(255.0 * x);
         }
         return;
      }

      if (bpp == 2) {
         short[] in16 = (short[]) raw;
         int n = Math.min(in16.length, out8.length);
         for (int i = 0; i < n; i++) {
            int v = in16[i] & 0xFFFF; // unsigned
            double x = Math.max(0.0, Math.min(1.0, (v - (double) min) * invRange));
            if (useGamma) {
               x = Math.pow(x, gamma);
            }
            out8[i] = (byte) (int) Math.round(255.0 * x);
         }
         return;
      }

      // Unsupported; black
      for (int i = 0; i < out8.length; i++) {
         out8[i] = 0;
      }
   }

   private void overlayDeltaT(byte[] plane8, int w, int h, double dtSec) {
      if (grayImg_ == null || g2d_ == null) {
         return;
      }

      // Copy into BufferedImage backing
      byte[] backing = ((DataBufferByte) grayImg_.getRaster().getDataBuffer()).getData();
      System.arraycopy(plane8, 0, backing, 0, Math.min(plane8.length, backing.length));

      String text = "\u0394t " + formatElapsedHhMmSsMmm(dtSec);

      // High-contrast label: black shadow then white text
      g2d_.setFont(new Font("Monospaced", Font.BOLD, 18));
      g2d_.setColor(java.awt.Color.BLACK);
      g2d_.drawString(text, 11, 23);
      g2d_.setColor(java.awt.Color.WHITE);
      g2d_.drawString(text, 10, 22);

      // Copy back out
      System.arraycopy(backing, 0, plane8, 0, Math.min(plane8.length, backing.length));
   }

   private String getSettingString(String key, String defaultValue) {
      try {
         if (settings_ != null) {
            String v = settings_.getString(key, defaultValue);
            if (v != null) {
               return v;
            }
         }
      } catch (Exception ignored) {}

      // Fallback to Preferences (keeps behavior working even if settings_ is null)
      try {
         return PREFS.get(key, defaultValue);
      } catch (Exception ignored) {
         return defaultValue;
      }
   }

   private double getSettingDouble(String key, double defaultValue) {
      try {
         if (settings_ != null) {
            double v = settings_.getDouble(key, Double.NaN);
            if (!Double.isNaN(v)) {
               return v;
            }
         }
      } catch (Exception ignored) {}

      // Fallback to Preferences
      try {
         return PREFS.getDouble(key, defaultValue);
      } catch (Exception ignored) {
         return defaultValue;
      }
   }

   /**
    * Minimal FFmpeg wrapper that drains stderr to avoid deadlocks.
    * Note: this intentionally discards stderr output. If you want it logged, add
    * a ring buffer or log lines at DEBUG (but be careful about volume).
    */
   private static final class FfmpegSession implements AutoCloseable {
      private final Process proc_;
      private final OutputStream stdin_;
      private final Thread stderrDrainer_;

      FfmpegSession(List<String> cmd) throws IOException {
         ProcessBuilder pb = new ProcessBuilder(cmd);
         pb.redirectErrorStream(false);
         proc_ = pb.start();
         stdin_ = new BufferedOutputStream(proc_.getOutputStream(), 1 << 20);

         stderrDrainer_ = new Thread(() -> drain(proc_.getErrorStream()), "ffmpeg-stderr");
         stderrDrainer_.setDaemon(true);
         stderrDrainer_.start();
      }

      void writeFrame(byte[] gray8) throws IOException {
         stdin_.write(gray8);
      }

      @Override
      public void close() throws IOException {
         try {
            stdin_.flush();
         } catch (Exception ignored) {}

         try {
            stdin_.close();
         } catch (Exception ignored) {}

         try {
            proc_.waitFor();
         } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
         } finally {
            proc_.destroy();
         }
      }

      private static void drain(InputStream in) {
         byte[] buf = new byte[8192];
         try {
            while (in.read(buf) >= 0) {
               // discard
            }
         } catch (IOException ignored) {
         } finally {
            try {
               in.close();
            } catch (Exception ignored2) {}
         }
      }
   }
}
