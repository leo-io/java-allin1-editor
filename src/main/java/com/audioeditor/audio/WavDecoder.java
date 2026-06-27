package com.audioeditor.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Path;

/** Converts a WAV-compatible source to the PCM format required by the mixer. */
public class WavDecoder {

    public DecodedPcmAudio decode(Path source) throws Exception {
        AudioInputStream input = AudioSystem.getAudioInputStream(source.toFile());
        try {
            AudioFormat base = input.getFormat();
            boolean ready = base.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                    && base.getSampleSizeInBits() == 16
                    && !base.isBigEndian();
            AudioFormat target = ready ? base : new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    base.getSampleRate(),
                    16,
                    base.getChannels(),
                    base.getChannels() * 2,
                    base.getSampleRate(),
                    false);
            if (!ready) {
                input = AudioSystem.getAudioInputStream(target, input);
            }
            return new DecodedPcmAudio(target, input.readAllBytes());
        } finally {
            input.close();
        }
    }
}
