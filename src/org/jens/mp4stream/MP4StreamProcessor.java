package org.jens.mp4stream;

import org.micromanager.PropertyMap;
import org.micromanager.Studio;
import org.micromanager.data.Image;
import org.micromanager.data.Processor;
import org.micromanager.data.ProcessorContext;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MP4StreamProcessor implements Processor {

   private final Studio studio_;
   private final int fps_;
   private final int crf_;
   private final String preset_;
   private final String ffmpeg_;
   private final boolean zerolat_;

   private String outPath_ = null;

   private Process ffmpegProc_;
   private OutputStream ffmpegIn_;
   private final BlockingQueue<byte[]> queue_ = new ArrayBlockingQueue<>(256);
   private volatile boolean running_ = true;
   private int dropped_ = 0;
   private Thread writer_;

   private long t0_ = -1;
   private int w_ = -1, h_ = -1;

   public MP4StreamProcessor(Studio studio, PropertyMap settings) {
      studio_ = studio;
      fps_    = settings.getInteger("fps", 30);
      crf_    = settings.getInteger("crf", 20);
      preset_ = settings.getString("preset", "veryfast");
      ffmpeg_ = settings.getString("ffmpeg", "ffmpeg");
      zerolat_= settings.getBoolean("zerolat", true);

      writer_ = new Thread(() -> {
         while (running_) {
            try {
               byte[] frame = queue_.take();
               if (ffmpegIn_ != null) ffmpegIn_.write(frame);
            } catch (InterruptedException | IOException ignore) {}
         }
      }, "MP4Writer");
      writer_.setDaemon(true);
      writer_.start();
   }

   @Override
   public void processImage(Image img, ProcessorContext ctx) {
      // Prompt once when the pipeline is enabled
      if (outPath_ == null) {
         outPath_ = promptForPath();
         if (outPath_ == null) {
            studio_.logs().showMessage("MP4 stream: Recording cancelled (no file selected).");
            ctx.outputImage(img);
            running_ = false;
            return;
         }
      }

      final int w = img.getWidth();
      final int h = img.getHeight();

      if (w != w_ || h != h_) {
         restartFFmpeg(w, h);
      }

      // Convert to 8-bit grayscale
      byte[] plane8;
      if (img.getBytesPerPixel() == 1) {
         Object raw = img.getRawPixels();
         if (raw instanceof byte[]) {
            // Copy so we do not modify MM's internal buffer during overlay
            byte[] src = (byte[]) raw;
            plane8 = new byte[src.length];
            System.arraycopy(src, 0, plane8, 0, src.length);
         } else if (raw instanceof ByteBuffer) {
            ByteBuffer bb = (ByteBuffer) raw;
            plane8 = new byte[bb.remaining()];
            bb.get(plane8);
         } else {
            plane8 = img.getRawPixelsCopy();
         }
      } else {
         short[] src = (short[]) img.getRawPixels();
         plane8 = new byte[src.length];
         for (int i = 0; i < src.length; i++) {
            plane8[i] = (byte) ((src[i] & 0xffff) >>> 8);
         }
      }

      // Overlay Δt in top-left (white text)
      stampTime(plane8, w, h);

      if (!queue_.offer(plane8)) dropped_++;

      // Forward original image unchanged to the rest of the pipeline
      ctx.outputImage(img);
   }

   @Override
   public void cleanup(ProcessorContext ctx) {
      running_ = false;
      if (writer_ != null) writer_.interrupt();
      destroyFFmpeg();
      if (dropped_ > 0) {
         studio_.logs().showMessage("MP4 stream dropped " + dropped_ + " frames (encoder back-pressure).");
      }
   }

   private void restartFFmpeg(int w, int h) {
      destroyFFmpeg();
      w_ = w; h_ = h;

      try {
         String cmd = ffmpeg_
               + " -loglevel error -y"
               + " -f rawvideo -pix_fmt gray8 -s " + w + "x" + h
               + " -r " + fps_ + " -i -"
               + " -c:v libx264 -preset " + preset_
               + " -crf " + crf_
               + (zerolat_ ? " -tune zerolatency" : "")
               + " -pix_fmt yuv420p"
               + " -movflags +faststart+frag_keyframe+empty_moov"
               + " \"" + outPath_ + "\"";

         ProcessBuilder pb = new ProcessBuilder(cmd.split(" "));
         pb.redirectErrorStream(true);
         ffmpegProc_ = pb.start();
         ffmpegIn_ = ffmpegProc_.getOutputStream();

         // Drain FFmpeg output to avoid deadlock on full pipe
         new Thread(() -> {
            try (BufferedReader br =
                  new BufferedReader(new InputStreamReader(ffmpegProc_.getInputStream()))) {
               while (br.readLine() != null) { /* ignore */ }
            } catch (IOException ignore) {}
         }, "FFmpeg-Drain").start();

         t0_ = -1;
         studio_.logs().logMessage("MP4 stream: ffmpeg started for " + w + "x" + h);

      } catch (Exception e) {
         studio_.logs().showError("MP4 stream: FFmpeg failed to start: " + e.getMessage());
      }
   }

   private void destroyFFmpeg() {
      try {
         if (ffmpegIn_ != null) ffmpegIn_.close();
      } catch (IOException ignore) {}
      if (ffmpegProc_ != null) ffmpegProc_.destroy();
      ffmpegProc_ = null;
      ffmpegIn_ = null;
   }

   private String promptForPath() {
      JFileChooser fc = new JFileChooser();
      fc.setDialogTitle("Choose MP4 output file");
      fc.setSelectedFile(new File("recording.mp4"));
      int r = fc.showSaveDialog(null);
      if (r == JFileChooser.APPROVE_OPTION) {
         File f = fc.getSelectedFile();
         if (!f.getName().toLowerCase().endsWith(".mp4")) {
            f = new File(f.getAbsolutePath() + ".mp4");
         }
         return f.getAbsolutePath();
      }
      return null;
   }

   private void stampTime(byte[] plane8, int w, int h) {
      long now = System.nanoTime();
      if (t0_ < 0) t0_ = now;
      double t = (now - t0_) / 1e9;

      BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
      bi.getRaster().setDataElements(0, 0, w, h, plane8);

      Graphics2D g = bi.createGraphics();
      g.setColor(Color.WHITE);
      g.setFont(new Font("Monospaced", Font.PLAIN, 24));
      g.drawString(String.format("t = %.3f s", t), 10, 30);
      g.dispose();

      bi.getRaster().getDataElements(0, 0, w, h, plane8);
   }
}
