package com.audioeditor.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;

/** Device seam used by the realtime engine and its tests. */
@FunctionalInterface
public interface AudioLineFactory {

    SourceDataLine open(AudioFormat format, int bufferBytes) throws Exception;
}
