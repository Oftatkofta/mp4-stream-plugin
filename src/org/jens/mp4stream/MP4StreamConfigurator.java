package org.jens.mp4stream;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.prefs.Preferences;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.micromanager.PropertyMap;
import org.micromanager.PropertyMaps;
import org.micromanager.data.ProcessorConfigurator;

public final class MP4StreamConfigurator implements ProcessorConfigurator {

   // Must be mutable so GUI changes persist in the pipeline settings.
   private PropertyMap settings_;

   private static final Preferences PREFS =
         Preferences.userNodeForPackage(MP4StreamConfigurator.class);

   // Settings keys
   public static final String KEY_OUTPUT_PATH = "mp4stream.outputPath";
   public static final String KEY_FFMPEG_PATH = "mp4stream.ffmpegPath";
   public static final String KEY_RECORDING_MODE = "mp4stream.recordingMode";
   public static final String KEY_TARGET_FPS = "mp4stream.targetFps";
   public static final String KEY_TIMELAPSE_FACTOR = "mp4stream.timelapseFactor";

   // Recording modes
   public static final String MODE_CONSTANT_FPS = "constant_fps";
   public static final String MODE_REALTIME = "realtime";
   public static final String MODE_TIMELAPSE = "timelapse";

   // Defaults
   public static final double DEFAULT_TARGET_FPS = 30.0;
   public static final double DEFAULT_TIMELAPSE_FACTOR = 10.0;

   public MP4StreamConfigurator(PropertyMap settings) {
      settings_ = settings;
   }

   @Override
   public PropertyMap getSettings() {
      return settings_;
   }

   private String getSetting(String key, String defaultVal) {
      String val = null;
      if (settings_ != null) {
         val = settings_.getString(key, null);
      }
      if (val == null || val.isEmpty()) {
         val = PREFS.get(key, defaultVal);
      }
      return val;
   }

   private double getSettingDouble(String key, double defaultVal) {
      Double val = null;
      if (settings_ != null) {
         val = settings_.getDouble(key, Double.NaN);
         if (Double.isNaN(val)) val = null;
      }
      if (val == null) {
         val = PREFS.getDouble(key, defaultVal);
      }
      return val;
   }

   @Override
   public void showGUI() {
      // Load current values
      String currentOut = getSetting(KEY_OUTPUT_PATH, "");
      String currentFfmpeg = getSetting(KEY_FFMPEG_PATH, "");
      String currentMode = getSetting(KEY_RECORDING_MODE, MODE_CONSTANT_FPS);
      double currentFps = getSettingDouble(KEY_TARGET_FPS, DEFAULT_TARGET_FPS);
      double currentTlFactor = getSettingDouble(KEY_TIMELAPSE_FACTOR, DEFAULT_TIMELAPSE_FACTOR);

      // Create dialog
      JDialog dialog = new JDialog();
      dialog.setTitle("MP4 Stream Settings");
      dialog.setModal(true);
      dialog.setLayout(new BorderLayout(10, 10));

      JPanel mainPanel = new JPanel(new GridBagLayout());
      mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
      GridBagConstraints gbc = new GridBagConstraints();
      gbc.insets = new Insets(5, 5, 5, 5);
      gbc.anchor = GridBagConstraints.WEST;
      gbc.fill = GridBagConstraints.HORIZONTAL;

      int row = 0;

      // === Output Path ===
      gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
      mainPanel.add(new JLabel("Output file:"), gbc);

      JTextField outField = new JTextField(currentOut, 30);
      gbc.gridx = 1; gbc.weightx = 1;
      mainPanel.add(outField, gbc);

      JButton browseOut = new JButton("Browse...");
      gbc.gridx = 2; gbc.weightx = 0;
      mainPanel.add(browseOut, gbc);

      browseOut.addActionListener(e -> {
         JFileChooser fc = new JFileChooser();
         fc.setDialogTitle("Select MP4 output file");
         fc.setFileFilter(new FileNameExtensionFilter("MP4 video (*.mp4)", "mp4"));
         String curr = outField.getText();
         fc.setSelectedFile(curr.isEmpty() ? new File("output.mp4") : new File(curr));
         if (fc.showSaveDialog(dialog) == JFileChooser.APPROVE_OPTION) {
            String path = fc.getSelectedFile().getAbsolutePath();
            if (!path.toLowerCase().endsWith(".mp4")) path += ".mp4";
            outField.setText(path);
         }
      });

      row++;

      // === FFmpeg Path ===
      gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
      mainPanel.add(new JLabel("FFmpeg path:"), gbc);

      JTextField ffmpegField = new JTextField(currentFfmpeg, 30);
      ffmpegField.setToolTipText("Leave empty to use 'ffmpeg' from system PATH");
      gbc.gridx = 1; gbc.weightx = 1;
      mainPanel.add(ffmpegField, gbc);

      JButton browseFfmpeg = new JButton("Browse...");
      gbc.gridx = 2; gbc.weightx = 0;
      mainPanel.add(browseFfmpeg, gbc);

      browseFfmpeg.addActionListener(e -> {
         JFileChooser fc = new JFileChooser();
         fc.setDialogTitle("Select ffmpeg executable");
         String curr = ffmpegField.getText();
         if (!curr.isEmpty()) fc.setSelectedFile(new File(curr));
         if (fc.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
            ffmpegField.setText(fc.getSelectedFile().getAbsolutePath());
         }
      });

      row++;

      // === Recording Mode ===
      JPanel modePanel = new JPanel(new GridBagLayout());
      modePanel.setBorder(BorderFactory.createTitledBorder("Recording Mode"));
      GridBagConstraints mgbc = new GridBagConstraints();
      mgbc.insets = new Insets(3, 5, 3, 5);
      mgbc.anchor = GridBagConstraints.WEST;
      mgbc.fill = GridBagConstraints.HORIZONTAL;

      ButtonGroup modeGroup = new ButtonGroup();

      // Constant FPS
      JRadioButton rbConstant = new JRadioButton("Constant FPS output");
      modeGroup.add(rbConstant);
      mgbc.gridx = 0; mgbc.gridy = 0; mgbc.gridwidth = 1;
      modePanel.add(rbConstant, mgbc);

      JSpinner fpsSpinner = new JSpinner(new SpinnerNumberModel(currentFps, 1.0, 120.0, 1.0));
      mgbc.gridx = 1;
      modePanel.add(fpsSpinner, mgbc);

      mgbc.gridx = 2;
      modePanel.add(new JLabel("fps"), mgbc);

      // Realtime (as fast as possible)
      JRadioButton rbRealtime = new JRadioButton("Real-time (as fast as camera)");
      modeGroup.add(rbRealtime);
      mgbc.gridx = 0; mgbc.gridy = 1; mgbc.gridwidth = 3;
      modePanel.add(rbRealtime, mgbc);

      // Timelapse
      JRadioButton rbTimelapse = new JRadioButton("Time-lapse compression");
      modeGroup.add(rbTimelapse);
      mgbc.gridx = 0; mgbc.gridy = 2; mgbc.gridwidth = 1;
      modePanel.add(rbTimelapse, mgbc);

      JSpinner tlSpinner = new JSpinner(new SpinnerNumberModel(currentTlFactor, 1.0, 10000.0, 1.0));
      mgbc.gridx = 1;
      modePanel.add(tlSpinner, mgbc);

      mgbc.gridx = 2;
      modePanel.add(new JLabel("x faster"), mgbc);

      // Set initial selection
      if (MODE_REALTIME.equals(currentMode)) {
         rbRealtime.setSelected(true);
      } else if (MODE_TIMELAPSE.equals(currentMode)) {
         rbTimelapse.setSelected(true);
      } else {
         rbConstant.setSelected(true);
      }

      // Enable/disable spinners based on mode
      Runnable updateSpinners = () -> {
         fpsSpinner.setEnabled(rbConstant.isSelected());
         tlSpinner.setEnabled(rbTimelapse.isSelected());
      };
      rbConstant.addActionListener(e -> updateSpinners.run());
      rbRealtime.addActionListener(e -> updateSpinners.run());
      rbTimelapse.addActionListener(e -> updateSpinners.run());
      updateSpinners.run();

      gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 3;
      mainPanel.add(modePanel, gbc);

      row++;

      // === Buttons ===
      JPanel buttonPanel = new JPanel();
      JButton okButton = new JButton("OK");
      JButton cancelButton = new JButton("Cancel");
      buttonPanel.add(okButton);
      buttonPanel.add(cancelButton);

      final boolean[] accepted = {false};

      okButton.addActionListener(e -> {
         accepted[0] = true;
         dialog.dispose();
      });

      cancelButton.addActionListener(e -> dialog.dispose());

      dialog.add(mainPanel, BorderLayout.CENTER);
      dialog.add(buttonPanel, BorderLayout.SOUTH);
      dialog.pack();
      dialog.setLocationRelativeTo(null);
      dialog.setVisible(true);

      if (!accepted[0]) {
         return; // User cancelled
      }

      // Save settings
      String outPath = outField.getText().trim();
      String ffmpegPath = ffmpegField.getText().trim();
      String mode = rbRealtime.isSelected() ? MODE_REALTIME :
                    rbTimelapse.isSelected() ? MODE_TIMELAPSE : MODE_CONSTANT_FPS;
      double fps = (Double) fpsSpinner.getValue();
      double tlFactor = (Double) tlSpinner.getValue();

      // Persist to preferences
      PREFS.put(KEY_OUTPUT_PATH, outPath);
      PREFS.put(KEY_FFMPEG_PATH, ffmpegPath);
      PREFS.put(KEY_RECORDING_MODE, mode);
      PREFS.putDouble(KEY_TARGET_FPS, fps);
      PREFS.putDouble(KEY_TIMELAPSE_FACTOR, tlFactor);

      // Build pipeline settings
      PropertyMap.Builder b = PropertyMaps.builder();
      b.putString(KEY_OUTPUT_PATH, outPath);
      b.putString(KEY_FFMPEG_PATH, ffmpegPath);
      b.putString(KEY_RECORDING_MODE, mode);
      b.putDouble(KEY_TARGET_FPS, fps);
      b.putDouble(KEY_TIMELAPSE_FACTOR, tlFactor);
      settings_ = b.build();
   }

   @Override
   public void cleanup() {
      // no-op
   }
}
