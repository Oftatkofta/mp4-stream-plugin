package org.jens.mp4stream;

import java.io.File;
import java.util.prefs.Preferences;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.micromanager.PropertyMap;
import org.micromanager.data.ProcessorConfigurator;

public final class MP4StreamConfigurator implements ProcessorConfigurator {

   private final PropertyMap settings_;

   private static final Preferences PREFS =
         Preferences.userNodeForPackage(MP4StreamConfigurator.class);

   public static final String KEY_OUTPUT_PATH = "outputPath";
   public static final String KEY_FFMPEG_PATH = "ffmpegPath"; // empty => use "ffmpeg" on PATH

   public MP4StreamConfigurator(PropertyMap settings) {
      settings_ = settings;
   }

   @Override
   public PropertyMap getSettings() {
      return settings_;
   }

   @Override
   public void showGUI() {
      // 1) Output file
      JFileChooser fc = new JFileChooser();
      fc.setDialogTitle("Select MP4 output file");
      fc.setFileFilter(new FileNameExtensionFilter("MP4 video (*.mp4)", "mp4"));

      String last = PREFS.get(KEY_OUTPUT_PATH, "");
      fc.setSelectedFile(last.isEmpty() ? new File("output.mp4") : new File(last));

      int result = fc.showSaveDialog(null);
      if (result != JFileChooser.APPROVE_OPTION) {
         // User cancelled; keep previous setting (if any). Processor will no-op if no outputPath.
         return;
      }

      File f = fc.getSelectedFile();
      String path = f.getAbsolutePath();
      if (!path.toLowerCase().endsWith(".mp4")) {
         path = path + ".mp4";
      }
      PREFS.put(KEY_OUTPUT_PATH, path);

      // 2) Optional: ask whether to select ffmpeg.exe explicitly
      int choose = JOptionPane.showConfirmDialog(
            null,
            "FFmpeg is expected to be available on PATH.\n\nSelect ffmpeg.exe manually?",
            "FFmpeg",
            JOptionPane.YES_NO_OPTION);

      if (choose == JOptionPane.YES_OPTION) {
         JFileChooser ff = new JFileChooser();
         ff.setDialogTitle("Select ffmpeg.exe");
         ff.setFileFilter(new FileNameExtensionFilter("ffmpeg.exe", "exe"));

         String lastFfmpeg = PREFS.get(KEY_FFMPEG_PATH, "");
         if (!lastFfmpeg.isEmpty()) {
            ff.setSelectedFile(new File(lastFfmpeg));
         }

         int r2 = ff.showOpenDialog(null);
         if (r2 == JFileChooser.APPROVE_OPTION) {
            PREFS.put(KEY_FFMPEG_PATH, ff.getSelectedFile().getAbsolutePath());
         }
      }
   }

   @Override
   public void cleanup() {
      // no-op
   }
}
