package org.jens.mp4stream;

import org.micromanager.PropertyMap;
import org.micromanager.Studio;
import org.micromanager.data.Processor;
import org.micromanager.data.ProcessorFactory;

public final class MP4StreamFactory implements ProcessorFactory {

   private final PropertyMap settings_;

   public MP4StreamFactory(PropertyMap settings) {
      settings_ = settings;
   }

   @Override
   public Processor createProcessor() {
      Studio studio = MP4StreamPlugin.getStudio();
      return new MP4StreamProcessor(studio, settings_);
   }
}
