package com.audioeditor.audio;

/**
 * Pure PCM generator for the metronome click.
 *
 * <p>Produces a short decaying-sine burst as a little-endian 16-bit PCM byte
 * array in the <em>playback</em> format (matching sample rate, channel count and
 * frame size of the loaded WAV). The click is <b>mixed directly into the primary
 * output stream</b> by {@link PcmWavPlaybackEngine}'s pump thread, so there is no
 * second {@code SourceDataLine}/{@code Clip} to glitch the main line, and click
 * timing is sample-accurate rather than tied to an EDT tick.
 */
public final class MetronomeClickSynthesizer {

    public static final int CLICK_DURATION_MILLISECONDS = 35;
    public static final double CLICK_TONE_FREQUENCY_HZ = 1500.0;
    public static final double CLICK_AMPLITUDE_SCALE = 0.6;

    private MetronomeClickSynthesizer() {
    }

    /**
     * Synthesize one click as interleaved little-endian 16-bit PCM in the given
     * playback format. The same mono burst is duplicated across every channel.
     *
     * @param sampleRateHz playback sample rate
     * @param channels     playback channel count (>= 1)
     * @return interleaved PCM bytes, {@code durationFrames * channels * 2} long
     */
    public static byte[] synthesizeClick(float sampleRateHz, int channels) {
        int ch = Math.max(1, channels);
        int frames = (int) (sampleRateHz * CLICK_DURATION_MILLISECONDS / 1000.0);
        byte[] data = new byte[frames * ch * 2];
        for (int i = 0; i < frames; i++) {
            double env = 1.0 - (i / (double) frames);                // linear decay to zero
            double v = Math.sin(2 * Math.PI * CLICK_TONE_FREQUENCY_HZ * i / sampleRateHz)
                    * env * CLICK_AMPLITUDE_SCALE;
            short sample = (short) (v * Short.MAX_VALUE);
            byte lo = (byte) (sample & 0xff);
            byte hi = (byte) ((sample >> 8) & 0xff);
            int base = i * ch * 2;
            for (int c = 0; c < ch; c++) {
                data[base + c * 2] = lo;
                data[base + c * 2 + 1] = hi;
            }
        }
        return data;
    }
}
