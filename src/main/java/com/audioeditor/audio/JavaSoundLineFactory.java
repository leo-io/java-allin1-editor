package com.audioeditor.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/** Opens the platform Java Sound output line. */
public final class JavaSoundLineFactory implements AudioLineFactory {

    @Override
    public SourceDataLine open(AudioFormat format, int bufferBytes) throws Exception {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format, bufferBytes);
        return line;
    }
}
