package org.jens.mp4stream;

import org.micromanager.PropertyMap;
import org.micromanager.PropertyMaps;
import org.micromanager.Studio;
import org.micromanager.data.ProcessorConfigurator;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

public class MP4StreamConfigurator implements ProcessorConfigurator {

   private final Studio studio_;
   private final JFrame frame_ = new JFrame("MP4 stream Settings");

   private final JTextField fpsField_ = new JTextField("30");
   private final JTextField crfField_ = new JTextField("20");
   private final JComboBox<String> presetBox_ =
         new JComboBox<>(new String[]{"ultrafast","superfast","veryfast","faster","fast","medium"});
   private final JCheckBox zerolat_ = new JCheckBox("Zero-latency tuning", true);
   private final JTextField ffmpegField_ = new JTextField("ffmpeg");

   public MP4StreamConfigurator(Studio studio, PropertyMap settings) {
      studio_ = studio;
      buildUI();
      load(settings);
   }

   private void buildUI() {
      JPanel p = new JPanel(new GridBagLayout());
      p.setBorder(new TitledBorder("MP4 stream Settings"));
      GridBagConstraints c = new GridBagConstraints();
      c.insets = new Insets(4,4,4,4);
      c.fill = GridBagConstraints.HORIZONTAL;

      int r = 0;
      c.gridx=0; c.gridy=r; p.add(new JLabel("Nominal FPS:"), c);
      c.gridx=1; p.add(fpsField_, c); r++;

      c.gridx=0; c.gridy=r; p.add(new JLabel("CRF:"), c);
      c.gridx=1; p.add(crfField_, c); r++;

      c.gridx=0; c.gridy=r; p.add(new JLabel("Preset:"), c);
      c.gridx=1; p.add(presetBox_, c); r++;

      c.gridx=0; c.gridy=r; p.add(new JLabel("FFmpeg path:"), c);
      c.gridx=1; p.add(ffmpegField_, c); r++;

      c.gridx=1; c.gridy=r; p.add(zerolat_, c); r++;

      frame_.add(p);
      frame_.pack();
      frame_.setLocationByPlatform(true);
   }

   private void load(PropertyMap pm) {
      if (pm == null) return;
      fpsField_.setText(String.valueOf(pm.getInteger("fps", 30)));
      crfField_.setText(String.valueOf(pm.getInteger("crf", 20)));
      presetBox_.setSelectedItem(pm.getString("preset", "veryfast"));
      ffmpegField_.setText(pm.getString("ffmpeg", "ffmpeg"));
      zerolat_.setSelected(pm.getBoolean("zerolat", true));
   }

   @Override
   public void showGUI() { frame_.setVisible(true); }

   @Override
   public void cleanup() { frame_.dispose(); }

   @Override
   public PropertyMap getSettings() {
      return PropertyMaps.builder()
            .putInteger("fps", Integer.parseInt(fpsField_.getText().trim()))
            .putInteger("crf", Integer.parseInt(crfField_.getText().trim()))
            .putString("preset", (String) presetBox_.getSelectedItem())
            .putString("ffmpeg", ffmpegField_.getText().trim())
            .putBoolean("zerolat", zerolat_.isSelected())
            .build();
   }
}
