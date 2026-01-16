package org.jens.mp4stream;

import org.micromanager.PropertyMap;
import org.micromanager.Studio;
import org.micromanager.data.Processor;
import org.micromanager.data.ProcessorFactory;

public class MP4StreamFactory implements ProcessorFactory {

   private final Studio studio_;
   private final PropertyMap settings_;

   public MP4StreamFactory(Studio studio, PropertyMap settings) {
      studio_ = studio;
      settings_ = settings;
   }

   @Override
   public Processor createProcessor() {
      return new MP4StreamProcessor(studio_, settings_);
   }
}

