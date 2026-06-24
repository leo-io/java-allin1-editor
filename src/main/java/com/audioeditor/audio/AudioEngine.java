package com.audioeditor.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.File;

/**
 * WAV playback engine built on {@code javax.sound.sampled}.
 *
 * <p>The file is decoded once into in-memory PCM. A dedicated pump thread owns
 * the {@link SourceDataLine} and is the <em>only</em> thread that calls the
 * blocking {@code write}/{@code flush} methods, so the UI thread never blocks on
 * audio I/O. Play / pause / stop / seek are expressed as volatile requests that
 * the pump thread services; the current position is computed lock-free from the
 * write cursor minus the bytes still queued in the line's buffer, which tracks
 * actual playback closely.
 */
public class AudioEngine {

    public interface EndListener {
        void onEnd();
    }

    private byte[] pcm = new byte[0];
    private int frameSize = 1;
    private float sampleRate = 44100f;
    private long totalFrames = 0;

    private volatile SourceDataLine line;
    private Thread pumpThread;
    private volatile boolean running = false;

    // request flags serviced by the pump thread
    private volatile boolean playRequested = false;
    private volatile boolean playing = false;
    private volatile long seekToFrame = -1;      // >=0 means a seek is pending
    private volatile long writeFrame = 0;         // next song frame to hand to the line
    // Published by the pump after each write so the EDT never touches the line monitor.
    private volatile long renderedFrame = 0;

    private Clip clickClip;
    private EndListener endListener;

    public void setEndListener(EndListener l) {
        this.endListener = l;
    }

    public boolean isLoaded() {
        return line != null && totalFrames > 0;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean hasMetronome() {
        return clickClip != null;
    }

    /** Load (and decode) a WAV file. Replaces any previously loaded audio. */
    public synchronized void load(File file) throws Exception {
        close();

        AudioInputStream in = AudioSystem.getAudioInputStream(file);
        AudioFormat base = in.getFormat();
        AudioFormat target = base;
        if (base.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
            target = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    base.getSampleRate(),
                    16,
                    base.getChannels(),
                    base.getChannels() * 2,
                    base.getSampleRate(),
                    false);
            in = AudioSystem.getAudioInputStream(target, in);
        }

        this.frameSize = Math.max(1, target.getFrameSize());
        this.sampleRate = target.getSampleRate();
        this.pcm = in.readAllBytes();
        in.close();
        this.totalFrames = pcm.length / frameSize;

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, target);
        SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
        // Small buffer (~100 ms) keeps the reported position close to what is heard.
        int bufBytes = Math.max(frameSize, (int) (sampleRate * 0.1) * frameSize);
        l.open(target, bufBytes);
        this.line = l;

        playRequested = false;
        playing = false;
        seekToFrame = -1;
        writeFrame = 0;
        renderedFrame = 0;

        buildClickClip();
        running = true;
        pumpThread = new Thread(this::pump, "audio-pump");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    private void pump() {
        final int chunkFrames = 1024;
        final int chunkBytes = chunkFrames * frameSize;
        final SourceDataLine l = line;
        while (running) {
            // Service a pending seek first.
            long s = seekToFrame;
            if (s >= 0) {
                l.flush();
                writeFrame = Math.max(0, Math.min(s, totalFrames));
                seekToFrame = -1;
                renderedFrame = writeFrame; // queue flushed; rendered == write cursor
            }

            if (playRequested) {
                if (writeFrame >= totalFrames) {
                    // reached the end
                    playRequested = false;
                    playing = false;
                    l.stop();
                    renderedFrame = totalFrames;
                    if (endListener != null) {
                        endListener.onEnd();
                    }
                    continue;
                }
                if (!playing) {
                    l.start();
                    playing = true;
                }
                long start = writeFrame * (long) frameSize;
                int len = (int) Math.min(chunkBytes, pcm.length - start);
                int written = l.write(pcm, (int) start, len); // blocking, no lock held
                writeFrame += written / frameSize;
                // Compute how many bytes are still buffered inside the line and
                // publish the true rendered position — all from the pump thread so
                // the EDT never has to touch the line monitor.
                int queued = Math.max(0, l.getBufferSize() - l.available());
                renderedFrame = Math.max(0, writeFrame - queued / frameSize);
            } else {
                if (playing) {
                    l.stop();
                    playing = false;
                    renderedFrame = writeFrame; // line stopped; queue drained
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    public void play() {
        if (!isLoaded()) {
            return;
        }
        if (writeFrame >= totalFrames) {
            seekToFrame = 0;
        }
        playRequested = true;
    }

    public void pause() {
        playRequested = false;
    }

    public void stop() {
        playRequested = false;
        seekToFrame = 0;
    }

    public void togglePlay() {
        if (playing) {
            pause();
        } else {
            play();
        }
    }

    public void seekSeconds(double seconds) {
        if (!isLoaded()) {
            return;
        }
        long f = (long) (seconds * sampleRate);
        seekToFrame = Math.max(0, Math.min(f, totalFrames));
    }

    /** Truly lock-free playback position: reads only volatile fields, never the line. */
    public double getPositionSeconds() {
        if (line == null) {
            return 0;
        }
        // A pending seek is reflected immediately so the UI feels snappy.
        long pending = seekToFrame;
        long f = pending >= 0 ? Math.max(0, Math.min(pending, totalFrames)) : renderedFrame;
        return Math.max(0, Math.min(f, totalFrames)) / (double) sampleRate;
    }

    public double getDurationSeconds() {
        return totalFrames / (double) sampleRate;
    }

    // ---- metronome -------------------------------------------------------

    private void buildClickClip() {
        try {
            float sr = 44100f;
            int ms = 35;
            int n = (int) (sr * ms / 1000);
            byte[] data = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                double env = 1.0 - (i / (double) n);
                double v = Math.sin(2 * Math.PI * 1500 * i / sr) * env * 0.6;
                short sample = (short) (v * Short.MAX_VALUE);
                data[2 * i] = (byte) (sample & 0xff);
                data[2 * i + 1] = (byte) ((sample >> 8) & 0xff);
            }
            AudioFormat fmt = new AudioFormat(sr, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(Clip.class, fmt);
            clickClip = (Clip) AudioSystem.getLine(info);
            clickClip.open(fmt, data, 0, data.length);
        } catch (Exception e) {
            clickClip = null; // metronome simply unavailable
        }
    }

    /** Fire one metronome click (non-blocking, overlaps playback). */
    public void playClick() {
        Clip c = clickClip;
        if (c == null) {
            return;
        }
        c.stop();
        c.setFramePosition(0);
        c.start();
    }

    // ---- lifecycle -------------------------------------------------------

    public synchronized void close() {
        running = false;
        playRequested = false;
        playing = false;
        Thread t = pumpThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
            }
            pumpThread = null;
        }
        SourceDataLine l = line;
        if (l != null) {
            try {
                l.stop();
                l.flush();
                l.close();
            } catch (Exception ignored) {
            }
            line = null;
        }
        if (clickClip != null) {
            clickClip.close();
            clickClip = null;
        }
        pcm = new byte[0];
        totalFrames = 0;
        writeFrame = 0;
        seekToFrame = -1;
        renderedFrame = 0;
    }
}
