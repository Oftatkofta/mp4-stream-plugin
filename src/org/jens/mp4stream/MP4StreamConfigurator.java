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
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
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

   // Overlay settings keys
   public static final String KEY_TIMESTAMP_ENABLED = "mp4stream.timestampEnabled";
   public static final String KEY_TIMESTAMP_COLOR = "mp4stream.timestampColor";
   public static final String KEY_TIMESTAMP_BACKGROUND = "mp4stream.timestampBackground";
   public static final String KEY_SCALEBAR_ENABLED = "mp4stream.scalebarEnabled";

   // Recording modes
   public static final String MODE_CONSTANT_FPS = "constant_fps";
   public static final String MODE_REALTIME = "realtime";
   public static final String MODE_TIMELAPSE = "timelapse";

   // Overlay text colors
   public static final String COLOR_WHITE = "white";
   public static final String COLOR_BLACK = "black";

   // Defaults
   public static final double DEFAULT_TARGET_FPS = 30.0;
   public static final double DEFAULT_TIMELAPSE_FACTOR = 10.0;
   public static final boolean DEFAULT_TIMESTAMP_ENABLED = true;
   public static final String DEFAULT_TIMESTAMP_COLOR = COLOR_WHITE;
   public static final boolean DEFAULT_TIMESTAMP_BACKGROUND = true;
   public static final boolean DEFAULT_SCALEBAR_ENABLED = false;

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

   private boolean getSettingBoolean(String key, boolean defaultVal) {
      if (settings_ != null) {
         try {
            return settings_.getBoolean(key, defaultVal);
         } catch (Exception ignored) {}
      }
      return PREFS.getBoolean(key, defaultVal);
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

      // Helper to save mode settings immediately to preferences
      Runnable saveModeToPrefs = () -> {
         String mode = rbRealtime.isSelected() ? MODE_REALTIME :
                       rbTimelapse.isSelected() ? MODE_TIMELAPSE : MODE_CONSTANT_FPS;
         PREFS.put(KEY_RECORDING_MODE, mode);
         PREFS.putDouble(KEY_TARGET_FPS, (Double) fpsSpinner.getValue());
         PREFS.putDouble(KEY_TIMELAPSE_FACTOR, (Double) tlSpinner.getValue());
      };

      // Enable/disable spinners based on mode, and save immediately
      Runnable updateSpinners = () -> {
         fpsSpinner.setEnabled(rbConstant.isSelected());
         tlSpinner.setEnabled(rbTimelapse.isSelected());
         saveModeToPrefs.run(); // Save mode immediately when changed
      };
      rbConstant.addActionListener(e -> updateSpinners.run());
      rbRealtime.addActionListener(e -> updateSpinners.run());
      rbTimelapse.addActionListener(e -> updateSpinners.run());

      // Also save immediately when spinner values change
      fpsSpinner.addChangeListener(e -> saveModeToPrefs.run());
      tlSpinner.addChangeListener(e -> saveModeToPrefs.run());

      updateSpinners.run();

      gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 3;
      mainPanel.add(modePanel, gbc);

      row++;

      // === Overlay Settings ===
      boolean currentTimestampEnabled = getSettingBoolean(KEY_TIMESTAMP_ENABLED, DEFAULT_TIMESTAMP_ENABLED);
      String currentTimestampColor = getSetting(KEY_TIMESTAMP_COLOR, DEFAULT_TIMESTAMP_COLOR);
      boolean currentTimestampBg = getSettingBoolean(KEY_TIMESTAMP_BACKGROUND, DEFAULT_TIMESTAMP_BACKGROUND);
      boolean currentScalebarEnabled = getSettingBoolean(KEY_SCALEBAR_ENABLED, DEFAULT_SCALEBAR_ENABLED);

      JPanel overlayPanel = new JPanel(new GridBagLayout());
      overlayPanel.setBorder(BorderFactory.createTitledBorder("Overlay"));
      GridBagConstraints ogbc = new GridBagConstraints();
      ogbc.insets = new Insets(3, 5, 3, 5);
      ogbc.anchor = GridBagConstraints.WEST;
      ogbc.fill = GridBagConstraints.HORIZONTAL;

      // Timestamp checkbox
      JCheckBox cbTimestamp = new JCheckBox("Show timestamp (Δt)", currentTimestampEnabled);
      ogbc.gridx = 0; ogbc.gridy = 0; ogbc.gridwidth = 2;
      overlayPanel.add(cbTimestamp, ogbc);

      // Text color
      ogbc.gridx = 0; ogbc.gridy = 1; ogbc.gridwidth = 1;
      overlayPanel.add(new JLabel("Text color:"), ogbc);

      JComboBox<String> colorCombo = new JComboBox<>(new String[]{"White", "Black"});
      colorCombo.setSelectedItem(COLOR_BLACK.equals(currentTimestampColor) ? "Black" : "White");
      ogbc.gridx = 1;
      overlayPanel.add(colorCombo, ogbc);

      // Background box
      JCheckBox cbBackground = new JCheckBox("Contrasting background", currentTimestampBg);
      ogbc.gridx = 0; ogbc.gridy = 2; ogbc.gridwidth = 2;
      overlayPanel.add(cbBackground, ogbc);

      // Scale bar
      JCheckBox cbScalebar = new JCheckBox("Show scale bar (requires pixel size)", currentScalebarEnabled);
      ogbc.gridx = 0; ogbc.gridy = 3; ogbc.gridwidth = 2;
      overlayPanel.add(cbScalebar, ogbc);

      // Enable/disable related controls
      Runnable updateOverlayControls = () -> {
         boolean tsEnabled = cbTimestamp.isSelected();
         colorCombo.setEnabled(tsEnabled);
         cbBackground.setEnabled(tsEnabled);
      };
      cbTimestamp.addActionListener(e -> updateOverlayControls.run());
      updateOverlayControls.run();

      // Save overlay settings immediately when changed
      Runnable saveOverlayToPrefs = () -> {
         PREFS.putBoolean(KEY_TIMESTAMP_ENABLED, cbTimestamp.isSelected());
         PREFS.put(KEY_TIMESTAMP_COLOR, "Black".equals(colorCombo.getSelectedItem()) ? COLOR_BLACK : COLOR_WHITE);
         PREFS.putBoolean(KEY_TIMESTAMP_BACKGROUND, cbBackground.isSelected());
         PREFS.putBoolean(KEY_SCALEBAR_ENABLED, cbScalebar.isSelected());
      };
      cbTimestamp.addActionListener(e -> saveOverlayToPrefs.run());
      colorCombo.addActionListener(e -> saveOverlayToPrefs.run());
      cbBackground.addActionListener(e -> saveOverlayToPrefs.run());
      cbScalebar.addActionListener(e -> saveOverlayToPrefs.run());

      gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 3;
      mainPanel.add(overlayPanel, gbc);

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

      // Get overlay settings
      boolean timestampEnabled = cbTimestamp.isSelected();
      String timestampColor = "Black".equals(colorCombo.getSelectedItem()) ? COLOR_BLACK : COLOR_WHITE;
      boolean timestampBg = cbBackground.isSelected();
      boolean scalebarEnabled = cbScalebar.isSelected();

      // Persist to preferences
      PREFS.put(KEY_OUTPUT_PATH, outPath);
      PREFS.put(KEY_FFMPEG_PATH, ffmpegPath);
      PREFS.put(KEY_RECORDING_MODE, mode);
      PREFS.putDouble(KEY_TARGET_FPS, fps);
      PREFS.putDouble(KEY_TIMELAPSE_FACTOR, tlFactor);
      PREFS.putBoolean(KEY_TIMESTAMP_ENABLED, timestampEnabled);
      PREFS.put(KEY_TIMESTAMP_COLOR, timestampColor);
      PREFS.putBoolean(KEY_TIMESTAMP_BACKGROUND, timestampBg);
      PREFS.putBoolean(KEY_SCALEBAR_ENABLED, scalebarEnabled);

      // Build pipeline settings
      PropertyMap.Builder b = PropertyMaps.builder();
      b.putString(KEY_OUTPUT_PATH, outPath);
      b.putString(KEY_FFMPEG_PATH, ffmpegPath);
      b.putString(KEY_RECORDING_MODE, mode);
      b.putDouble(KEY_TARGET_FPS, fps);
      b.putDouble(KEY_TIMELAPSE_FACTOR, tlFactor);
      b.putBoolean(KEY_TIMESTAMP_ENABLED, timestampEnabled);
      b.putString(KEY_TIMESTAMP_COLOR, timestampColor);
      b.putBoolean(KEY_TIMESTAMP_BACKGROUND, timestampBg);
      b.putBoolean(KEY_SCALEBAR_ENABLED, scalebarEnabled);
      settings_ = b.build();
   }

   @Override
   public void cleanup() {
      // no-op
   }
}
