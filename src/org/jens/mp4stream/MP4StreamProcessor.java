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
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import java.time.Instant;

import org.micromanager.data.Image;
import org.micromanager.data.Processor;
import org.micromanager.data.ProcessorContext;
import org.micromanager.data.SummaryMetadata;
import org.micromanager.data.Metadata;

import org.micromanager.Studio;
import org.micromanager.PropertyMap;
import org.micromanager.LogManager;

import org.micromanager.display.DisplayWindow;
import org.micromanager.display.DisplaySettings;
import org.micromanager.display.ChannelDisplaySettings;
import org.micromanager.display.ComponentDisplaySettings;






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
   private FfmpegSession ff_ = null;
   private int segmentIndex_ = 0;
   private final Object ffLock_ = new Object();

   // Timing for Δt overlay
   private boolean t0IsElapsedMs_ = false;
   private double t0ElapsedMs_ = 0.0;

   private boolean t0IsReceivedTime_ = false;
   private long t0ReceivedNs_ = 0L;

   private long t0WallNanos_ = 0L; // last fallback

   // watchdog timer
   private volatile long lastFrameNanos_ = 0L;
   private Thread watchdog_ = null;
   private volatile boolean watchdogRun_ = false;

   // Constant-FPS output 
   private static final double TARGET_FPS = 30.0;
   private long nextOutFrameIndex_ = 0;
   private boolean haveLastFrame_ = false;
   private byte[] lastFrame8_ = null;
   

   private static final boolean USE_LIVE_DISPLAY_SCALING = true;

   private static final String LOG_PREFIX = "[MP4Stream] ";

   private LogManager logs() {
      return (studio_ == null) ? null : studio_.logs();
   }
   
   private void logInfo(String msg) {
      LogManager lm = logs();
      if (lm != null) {
         lm.logMessage(LOG_PREFIX + msg);
      }
   }
   
   private void logDebug(String msg) {
      LogManager lm = logs();
      if (lm != null) {
         lm.logDebugMessage(LOG_PREFIX + msg);
      }
   }
   
   private void logError(String msg, Exception e) {
      LogManager lm = logs();
      if (lm != null) {
         lm.logError(e, LOG_PREFIX + msg);
      }
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
         logError("Processor exception while handling frame.", e);

      } finally {
         context.outputImage(img);
      }
   }

   @Override
   public void cleanup(ProcessorContext context) {
      stopFfmpeg();
      stopWatchdog();
      disposeOverlay();
   }

   private void recordFrameIfConfigured(Image img) throws IOException {
      final String outPath = getSettingString(MP4StreamConfigurator.KEY_OUTPUT_PATH, "");
      if (outPath == null || outPath.trim().isEmpty()) {
         return;
      }
   
      // Only record when Live is running or an acquisition (MDA) is running.
      if (!shouldRecordNow()) {
         return;
      }
   
      final int w = img.getWidth();
      final int h = img.getHeight();
      if (w <= 0 || h <= 0) {
         return;
      }
   
      // Start if needed, restart on dimension change
      if (ff_ == null) {
         startFfmpegForDimensions(outPath, w, h, img);
      } else if (w != width_ || h != height_) {
         startFfmpegForDimensions(outPath, w, h, img);
      }
   
      ensureBuffersForDimensions(w, h);
   
      // Convert incoming pixels to gray8
      if (USE_LIVE_DISPLAY_SCALING) {
         DisplayScaling sc = getLiveDisplayScaling(img);
         convertToGray8WithScaling(img, plane8_, sc.min, sc.max, sc.gamma);
      } else {
         convertToGray8(img, plane8_);
      }
   
      // Elapsed time (seconds) since segment start (as defined by initTimeZero)
      double dtSec = elapsedSinceT0Seconds(img);
   
      // Write exactly once using the single CFR path
      writeCfrFrameLocked(plane8_, w, h, dtSec);
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
      // Prefer acquisition elapsed time
      try {
         Metadata md = img.getMetadata();
         if (t0IsElapsedMs_ && md != null && md.hasElapsedTimeMs()) {
            Double ms = md.getElapsedTimeMs();
            if (ms != null) {
               double dtMs = ms.doubleValue() - t0ElapsedMs_;
               if (dtMs < 0) dtMs = 0;
               return dtMs / 1000.0;
            }
         }
      } catch (Exception ignored) {}
   
      // Fallback: received time
      try {
         Metadata md = img.getMetadata();
         if (t0IsReceivedTime_ && md != null) {
            String rt = md.getReceivedTime();
            if (rt != null && !rt.trim().isEmpty()) {
               Instant inst = Instant.parse(rt.trim());
               long ns = inst.getEpochSecond() * 1_000_000_000L + inst.getNano();
               long dtNs = ns - t0ReceivedNs_;
               if (dtNs < 0) dtNs = 0;
               return dtNs / 1_000_000_000.0;
            }
         }
      } catch (Exception ignored) {}
   
      // Last fallback: wall clock since segment start
      long dtNs = System.nanoTime() - t0WallNanos_;
      if (dtNs < 0) dtNs = 0;
      return dtNs / 1_000_000_000.0;
   }
   
   
   private void startFfmpegForDimensions(String baseOutPath, int w, int h, Image firstImg) throws IOException {

      // Close any existing stream
      stopFfmpeg();

      width_ = w;
      height_ = h;
      segmentIndex_++;

      // MP4 cannot change resolution mid-stream. Segment output to new file.
      final String segPath = makeSegmentPath(baseOutPath, w, h, segmentIndex_);

      final String ffmpegPath = getSettingString(MP4StreamConfigurator.KEY_FFMPEG_PATH, "");


      final String exe = (ffmpegPath == null || ffmpegPath.trim().isEmpty()) ? "ffmpeg" : ffmpegPath;
      
      // Build FFmpeg command as cmd-list
      List<String> cmd = new ArrayList<>();
      cmd.add(exe);
      cmd.add("-y"); // overwrite existing file
      cmd.add("-f"); cmd.add("rawvideo"); // input format
      cmd.add("-pix_fmt"); cmd.add("gray"); // pixel format
      cmd.add("-s"); cmd.add(w + "x" + h); // size
      
      String fps = String.format(java.util.Locale.US, "%.3f", TARGET_FPS);
      cmd.add("-r"); cmd.add(fps); // frame rate
      cmd.add("-i"); cmd.add("-"); // input from stdin

      // video encoding (CPU-only)
      cmd.add("-an"); // no audio
      cmd.add("-c:v"); cmd.add("libx264"); // video codec
      cmd.add("-preset"); cmd.add("veryfast"); // preset
      cmd.add("-crf"); cmd.add("18"); // constant rate factor
      cmd.add("-pix_fmt"); cmd.add("yuv420p"); // pixel format

      cmd.add(segPath); // output file name

      //FFMPEG command and filename to log
      logInfo("Starting FFmpeg: " + segPath + " (" + w + "x" + h + " @" + String.format(java.util.Locale.US, "%.3f", TARGET_FPS) + " fps)");
      logDebug("FFmpeg command: " + cmd.toString());

      ff_ = new FfmpegSession(cmd);
      initTimeZero(firstImg);
      nextOutFrameIndex_ = 0;
      haveLastFrame_ = false;
      lastFrame8_ = null;


      // Recreate overlay resources for this dimension
      disposeOverlay();
      ensureBuffersForDimensions(w, h);

      // Start watchdog
      lastFrameNanos_ = System.nanoTime();
      startWatchdog();

   }

   private void initTimeZero(Image img) {
      t0IsElapsedMs_ = false;
      try {
         org.micromanager.data.Metadata md = img.getMetadata();
         if (md != null && md.hasElapsedTimeMs()) {
            Double ms = md.getElapsedTimeMs();
            if (ms != null) {
               t0ElapsedMs_ = ms.doubleValue();
               t0IsElapsedMs_ = true;
               return;
            }
         }
      } catch (Exception ignored) {}
      t0ElapsedMs_ = 0.0;
   }

   private void writeCfrFrameLocked(byte[] frame8, int w, int h, double dtSec) throws IOException {
      long targetIndex = (long) Math.floor((dtSec * TARGET_FPS) + 1e-9);
   
      synchronized (ffLock_) {
         if (ff_ == null) {
            return;
         }
   
         if (lastFrame8_ == null || lastFrame8_.length != frame8.length) {
            lastFrame8_ = new byte[frame8.length];
            haveLastFrame_ = false;
         }
   
         if (haveLastFrame_) {
            while (nextOutFrameIndex_ < targetIndex) {
               ff_.writeFrame(lastFrame8_);
               nextOutFrameIndex_++;
            }
         } else {
            nextOutFrameIndex_ = targetIndex;
         }
   
         if (nextOutFrameIndex_ == targetIndex) {
            // Apply overlay into a working buffer before writing.
            // If you want to avoid modifying input array, copy first.
            overlayDeltaT(frame8, w, h, dtSec);
   
            ff_.writeFrame(frame8);
            nextOutFrameIndex_++;
   
            System.arraycopy(frame8, 0, lastFrame8_, 0, frame8.length);
            haveLastFrame_ = true;
         } else {
            // Drop but keep as most recent candidate
            System.arraycopy(frame8, 0, lastFrame8_, 0, frame8.length);
            haveLastFrame_ = true;
         }
      }
   
      lastFrameNanos_ = System.nanoTime();
   }
   
   
   private double elapsedSinceT0Seconds(Image img) {
      if (!t0IsElapsedMs_) {
         return 0.0;
      }
      try {
         org.micromanager.data.Metadata md = img.getMetadata();
         if (md != null && md.hasElapsedTimeMs()) {
            Double ms = md.getElapsedTimeMs();
            if (ms != null) {
               double dt = ms.doubleValue() - t0ElapsedMs_;
               if (dt < 0) dt = 0;
               return dt / 1000.0;
            }
         }
      } catch (Exception ignored) {}
      return 0.0;
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


   private void startWatchdog() {
      if (watchdog_ != null) {
         return;
      }
      watchdogRun_ = true;
      watchdog_ = new Thread(() -> {
         final long timeoutNanos = 1_000_000_000L; // 1 second
         while (watchdogRun_) {
            try {
               Thread.sleep(200);
            } catch (InterruptedException ie) {
               Thread.currentThread().interrupt();
               return;
            }
            if (ff_ != null) {
               long idle = System.nanoTime() - lastFrameNanos_;
               if (idle > timeoutNanos) {
                  stopFfmpeg(); // closes stdin => MP4 finalized
               }
            }
         }
      }, "mp4stream-watchdog");
      watchdog_.setDaemon(true);
      watchdog_.start();
   }
   
   private void stopWatchdog() {
      watchdogRun_ = false;
      if (watchdog_ != null) {
         watchdog_.interrupt();
      }
      watchdog_ = null;
   }
   
   
   private static String makeSegmentPath(String baseOutPath, int w, int h, int idx) {
      File f = new File(baseOutPath);
      String name = f.getName();
      String parent = f.getParent();
      if (parent == null) parent = ".";

      String stem = name;
      if (stem.toLowerCase().endsWith(".mp4")) {
         stem = stem.substring(0, stem.length() - 4);
      }
      String segName = String.format("%s_%dx%d_seg%03d.mp4", stem, w, h, idx);
      return new File(parent, segName).getAbsolutePath();
   }

   private void stopFfmpeg() {
      synchronized (ffLock_) {
         if (ff_ != null) {
            try {
               ff_.close();
            } catch (Exception ignored) {
            }
            ff_ = null;
         }
      }
   }
   
   
   

   private void ensureBuffersForDimensions(int w, int h) {
      int n = w * h;
      if (plane8_ == null || plane8_.length != n) {
         plane8_ = new byte[n];
      }

      if (grayImg_ == null || grayImg_.getWidth() != w || grayImg_.getHeight() != h) {
         // Build BufferedImage backed by plane8_
         grayImg_ = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
         byte[] backing = ((DataBufferByte) grayImg_.getRaster().getDataBuffer()).getData();

         // We will copy plane8_ into backing each frame before drawing (cheap),
         // then copy backing back into plane8_ (also cheap). This avoids fragile raster aliasing.
         // Alternative (alias same array) is possible but more error-prone.
         g2d_ = grayImg_.createGraphics();
         g2d_.setFont(new Font("SansSerif", Font.BOLD, 18));
         g2d_.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
         g2d_.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      }
   }

   private void disposeOverlay() {
      if (g2d_ != null) {
         try { g2d_.dispose(); } catch (Exception ignored) {}
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
      // avoid divide-by-zero; fall back to simple mapping
      convertToGray8(img, out8);
      return;
   }
   if (!(gamma > 0.0)) {
      gamma = 1.0;
   }
   
   final double invRange = 1.0 / (double) (max - min);
   // Micro-Manager display gamma behaves like: out = in^gamma
   final double gammaExp = gamma;
   
   if (bpp == 1) {
      byte[] in = (byte[]) raw;
      int n = Math.min(in.length, out8.length);
      for (int i = 0; i < n; i++) {
         int v = in[i] & 0xFF;
         double x = (v - (double) min) * invRange;
         if (x < 0) x = 0;
         if (x > 1) x = 1;
         if (gamma != 1.0) {
            x = Math.pow(x, gammaExp);
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
         double x = (v - (double) min) * invRange;
         if (x < 0) x = 0;
         if (x > 1) x = 1;
         if (gamma != 1.0) {
            x = Math.pow(x, gammaExp);
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

      // Draw a simple high-contrast label: black shadow then white text
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
      } catch (Exception ignored) {
      }
      // Fallback to Preferences (keeps behavior working even if settings_ is null)
      try {
         return PREFS.get(key, defaultValue);
      } catch (Exception ignored) {
         return defaultValue;
      }
   }
   

   // Minimal FFmpeg wrapper that drains stderr to avoid deadlocks.
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
               // discard; you can log later if desired
            }
         } catch (IOException ignored) {
         } finally {
            try { in.close(); } catch (Exception ignored2) {}
         }
      }
   }
}
