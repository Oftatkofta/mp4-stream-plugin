package org.jens.mp4stream;

import org.micromanager.Studio;
import org.micromanager.data.Processor;
import org.micromanager.data.ProcessorFactory;

public final class MP4StreamFactory implements ProcessorFactory {

   @Override
   public Processor createProcessor() {
      Studio studio = MP4StreamPlugin.getStudio();
      return new MP4StreamProcessor(studio);
   }
}
