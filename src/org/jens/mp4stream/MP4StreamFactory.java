package org.jens.mp4stream;

import org.micromanager.data.Processor;
import org.micromanager.data.ProcessorFactory;

public final class MP4StreamFactory implements ProcessorFactory {
   @Override
   public Processor createProcessor() {
      return new MP4StreamProcessor();
   }
}
