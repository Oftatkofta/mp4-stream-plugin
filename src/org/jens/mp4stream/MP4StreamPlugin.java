package org.jens.mp4stream;

import org.micromanager.MMPlugin;
import org.micromanager.PropertyMap;
import org.micromanager.Studio;
import org.micromanager.data.ProcessorConfigurator;
import org.micromanager.data.ProcessorFactory;
import org.micromanager.data.ProcessorPlugin;
import org.scijava.plugin.Plugin;

@Plugin(type = ProcessorPlugin.class)
public final class MP4StreamPlugin implements ProcessorPlugin, MMPlugin {

   private Studio studio_;

   @Override
   public void setContext(Studio studio) {
      studio_ = studio;
   }

   @Override
   public ProcessorConfigurator createConfigurator(PropertyMap settings) {
      return new MP4StreamConfigurator(settings);
   }

   @Override
   public ProcessorFactory createFactory(PropertyMap settings) {
      return new MP4StreamFactory();
   }

   @Override
   public String getName() {
      return "MP4 Stream (FFmpeg)";
   }

   @Override
   public String getHelpText() {
      return "Streams incoming frames to FFmpeg for H.264 MP4 encoding.";
   }

   @Override
   public String getVersion() {
      return "0.1.0";
   }

   @Override
   public String getCopyright() {
      return "Copyright (c) 2026";
   }
}
