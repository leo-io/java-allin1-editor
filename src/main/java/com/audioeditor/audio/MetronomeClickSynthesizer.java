package com.audioeditor.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns PCM synthesis and {@link Clip} playback of metronome clicks.
 *
 * <p>A short decaying sine burst is synthesised once into an in-memory clip;
 * each {@link #triggerClickPlayback()} call restarts that clip, giving a
 * low-latency, non-blocking click that overlaps WAV playback.
 */
public class MetronomeClickSynthesizer {

    public static final float CLICK_SYNTHESIS_SAMPLE_RATE_HZ = 44100f;
    public static final int CLICK_DURATION_MILLISECONDS = 35;
    public static final double CLICK_TONE_FREQUENCY_HZ = 1500.0;
    public static final double CLICK_AMPLITUDE_SCALE = 0.6;

    private volatile Clip synthesizedClickClip;
    // Serializes clip restart against close so a click task never touches a
    // clip that closeClickClip() is releasing.
    private final Object clipLock = new Object();
    // Dedicated single-thread executor so the native Clip stop/setFramePosition/
    // start calls never run on the EDT tick — they would jitter playback.
    private final ExecutorService clickExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "metronome-click");
        t.setDaemon(true);
        return t;
    });

    /** Synthesise (or re-synthesise) the click clip. Safe to call on reload. */
    public void initializeSynthesizedClickClip() {
        try {
            float sr = CLICK_SYNTHESIS_SAMPLE_RATE_HZ;
            int ms = CLICK_DURATION_MILLISECONDS;
            int n = (int) (sr * ms / 1000);
            byte[] data = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                double env = 1.0 - (i / (double) n);
                double v = Math.sin(2 * Math.PI * CLICK_TONE_FREQUENCY_HZ * i / sr) * env * CLICK_AMPLITUDE_SCALE;
                short sample = (short) (v * Short.MAX_VALUE);
                data[2 * i] = (byte) (sample & 0xff);
                data[2 * i + 1] = (byte) ((sample >> 8) & 0xff);
            }
            AudioFormat fmt = new AudioFormat(sr, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(Clip.class, fmt);
            Clip clip = (Clip) AudioSystem.getLine(info);
            clip.open(fmt, data, 0, data.length);
            synchronized (clipLock) {
                synthesizedClickClip = clip;
            }
        } catch (Exception e) {
            synchronized (clipLock) {
                synthesizedClickClip = null; // metronome simply unavailable
            }
        }
    }

    /**
     * Fire one metronome click. Non-blocking: the native Clip restart is
     * dispatched to a background thread so the EDT tick never stalls on it.
     */
    public void triggerClickPlayback() {
        clickExecutor.execute(this::runClickRestart);
    }

    private void runClickRestart() {
        synchronized (clipLock) {
            Clip c = synthesizedClickClip;
            if (c == null) {
                return;
            }
            try {
                c.stop();
                c.setFramePosition(0);
                c.start();
            } catch (Exception ignored) {
                // A clip closed mid-click is harmless; just drop this click.
            }
        }
    }

    public boolean isClickClipAvailable() {
        synchronized (clipLock) {
            return synthesizedClickClip != null;
        }
    }

    /** Release the click clip's native resources. */
    public void closeClickClip() {
        synchronized (clipLock) {
            if (synthesizedClickClip != null) {
                try {
                    synthesizedClickClip.close();
                } catch (Exception ignored) {
                }
                synthesizedClickClip = null;
            }
        }
        // The executor stays alive (daemon) across reloads; it is reclaimed on JVM exit.
    }
}
