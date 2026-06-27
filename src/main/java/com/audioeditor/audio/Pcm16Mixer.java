package com.audioeditor.audio;

/** Saturating little-endian signed 16-bit PCM mixing. */
public final class Pcm16Mixer {

    private Pcm16Mixer() {
    }

    public static void add(byte[] destination, int destinationFrame,
                           byte[] source, int sourceFrame,
                           int frameCount, int channels) {
        int bytesPerFrame = channels * 2;
        for (int frame = 0; frame < frameCount; frame++) {
            int destinationBase = (destinationFrame + frame) * bytesPerFrame;
            int sourceBase = (sourceFrame + frame) * bytesPerFrame;
            for (int channel = 0; channel < channels; channel++) {
                int destinationIndex = destinationBase + channel * 2;
                int sourceIndex = sourceBase + channel * 2;
                int mixed = readSample(destination, destinationIndex) + readSample(source, sourceIndex);
                mixed = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed));
                destination[destinationIndex] = (byte) (mixed & 0xff);
                destination[destinationIndex + 1] = (byte) ((mixed >> 8) & 0xff);
            }
        }
    }

    private static short readSample(byte[] bytes, int index) {
        return (short) ((bytes[index] & 0xff) | (bytes[index + 1] << 8));
    }
}
