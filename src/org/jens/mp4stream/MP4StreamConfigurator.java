package org.jens.mp4stream;

import java.io.File;
import java.util.prefs.Preferences;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.micromanager.PropertyMap;
import org.micromanager.PropertyMaps;
import org.micromanager.data.ProcessorConfigurator;

public final class MP4StreamConfigurator implements ProcessorConfigurator {

   // Must be mutable so GUI changes persist in the pipeline settings.
   private PropertyMap settings_;

   private static final Preferences PREFS =
         Preferences.userNodeForPackage(MP4StreamConfigurator.class);

   // Use stable, namespaced keys to avoid collisions with other plugins/pipelines.
   public static final String KEY_OUTPUT_PATH = "mp4stream.outputPath";
   public static final String KEY_FFMPEG_PATH = "mp4stream.ffmpegPath"; // empty => use "ffmpeg" on PATH

   public MP4StreamConfigurator(PropertyMap settings) {
      settings_ = settings;
   }

   @Override
   public PropertyMap getSettings() {
      return settings_;
   }

   @Override
   public void showGUI() {
      // Load current values from pipeline settings (preferred), fallback to prefs.
      String currentOut = "";
      String currentFfmpeg = "";
      if (settings_ != null) {
         currentOut = settings_.getString(KEY_OUTPUT_PATH, "");
         currentFfmpeg = settings_.getString(KEY_FFMPEG_PATH, "");
      }
      if (currentOut == null || currentOut.isEmpty()) {
         currentOut = PREFS.get(KEY_OUTPUT_PATH, "");
      }
      if (currentFfmpeg == null || currentFfmpeg.isEmpty()) {
         currentFfmpeg = PREFS.get(KEY_FFMPEG_PATH, "");
      }

      // 1) Output file
      JFileChooser fc = new JFileChooser();
      fc.setDialogTitle("Select MP4 output file");
      fc.setFileFilter(new FileNameExtensionFilter("MP4 video (*.mp4)", "mp4"));
      fc.setSelectedFile(currentOut.isEmpty() ? new File("output.mp4") : new File(currentOut));

      int result = fc.showSaveDialog(null);
      if (result != JFileChooser.APPROVE_OPTION) {
         // User cancelled; keep existing settings unchanged.
         return;
      }

      File f = fc.getSelectedFile();
      String outPath = f.getAbsolutePath();
      if (!outPath.toLowerCase().endsWith(".mp4")) {
         outPath = outPath + ".mp4";
      }

      // Save "last used" for convenience
      PREFS.put(KEY_OUTPUT_PATH, outPath);

      // 2) Optional: ask whether to select ffmpeg.exe explicitly
      int choose = JOptionPane.showConfirmDialog(
            null,
            "FFmpeg is expected to be available on PATH.\n\nSelect ffmpeg.exe manually?",
            "FFmpeg",
            JOptionPane.YES_NO_OPTION);

      String ffmpegPath = currentFfmpeg; // default: keep existing
      if (choose == JOptionPane.YES_OPTION) {
         JFileChooser ff = new JFileChooser();
         ff.setDialogTitle("Select ffmpeg.exe");
         ff.setFileFilter(new FileNameExtensionFilter("ffmpeg.exe", "exe"));

         if (!currentFfmpeg.isEmpty()) {
            ff.setSelectedFile(new File(currentFfmpeg));
         }

         int r2 = ff.showOpenDialog(null);
         if (r2 == JFileChooser.APPROVE_OPTION) {
            ffmpegPath = ff.getSelectedFile().getAbsolutePath();
            PREFS.put(KEY_FFMPEG_PATH, ffmpegPath);
         }
      }

      // Persist to PIPELINE settings (this is what fixes enable-toggle inconsistency)
      PropertyMap.Builder b = PropertyMaps.builder();
      b.putString(KEY_OUTPUT_PATH, outPath);
      b.putString(KEY_FFMPEG_PATH, ffmpegPath == null ? "" : ffmpegPath);
      settings_ = b.build();
   }

   @Override
   public void cleanup() {
      // no-op
   }
}
