package org.jens.mp4stream;

import org.micromanager.MMPlugin;
import org.micromanager.PropertyMap;
import org.micromanager.Studio;
import org.micromanager.data.ProcessorConfigurator;
import org.micromanager.data.ProcessorFactory;
import org.micromanager.data.ProcessorPlugin;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.SciJavaPlugin;

@Plugin(type = ProcessorPlugin.class)
public class MP4StreamPlugin implements ProcessorPlugin, SciJavaPlugin, MMPlugin {

   private Studio studio_;

   @Override
   public void setContext(Studio studio) { studio_ = studio; }

   @Override
   public String getName() { return "MP4 stream"; }

   @Override
   public String getHelpText() {
      return "CPU-only H.264 MP4 recorder for grayscale scientific cameras with Δt overlay.";
   }

   @Override
   public String getVersion() { return "1.0.0"; }

   @Override
   public String getCopyright() {
      return "© 2026 Jens Eriksson";
   }

   @Override
   public ProcessorConfigurator createConfigurator(PropertyMap settings) {
      return new MP4StreamConfigurator(studio_, settings);
   }

   @Override
   public ProcessorFactory createFactory(PropertyMap settings) {
      return new MP4StreamFactory(studio_, settings);
   }
}