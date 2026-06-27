package com.audioeditor.audio;

import javax.sound.sampled.AudioFormat;

/** Decoded 16-bit little-endian PCM ready for the playback pump. */
public record DecodedPcmAudio(AudioFormat format, byte[] bytes) {

    public int bytesPerFrame() {
        return Math.max(1, format.getFrameSize());
    }

    public int channels() {
        return Math.max(1, format.getChannels());
    }

    public float sampleRate() {
        return format.getSampleRate();
    }

    public long frameCount() {
        return bytes.length / bytesPerFrame();
    }
}
