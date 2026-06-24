package com.audioeditor.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.File;
import java.util.logging.Logger;

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
public class PcmWavPlaybackEngine {

    private static final Logger LOG = Logger.getLogger(PcmWavPlaybackEngine.class.getName());

    public interface PlaybackCompletionListener {
        void onEnd();
    }

    private byte[] decodedPcmAudioBytes = new byte[0];
    private int bytesPerAudioFrame = 1;
    private float sampleRateInHz = 44100f;
    private long totalAudioFrameCount = 0;

    private volatile SourceDataLine activeSourceDataLine;
    private Thread audioPumpThread;
    private volatile boolean audioPumpThreadIsRunning = false;

    // request flags serviced by the pump thread
    private volatile boolean playbackIsRequested = false;
    private volatile boolean playbackIsActive = false;
    private volatile long pendingSeekTargetFrame = -1;      // >=0 means a seek is pending
    private volatile long nextWriteCursorFrame = 0;         // next song frame to hand to the line
    // Published by the pump after each write so the EDT never touches the line monitor.
    private volatile long lastRenderedAudioFrame = 0;

    // Monitor used to wake the pump thread from its idle wait when play/seek is requested.
    private final Object pumpWaitLock = new Object();
    private final MetronomeClickSynthesizer metronomeClickSynthesizer = new MetronomeClickSynthesizer();
    private PlaybackCompletionListener playbackCompletionListener;

    public void setPlaybackCompletionListener(PlaybackCompletionListener listener) {
        this.playbackCompletionListener = listener;
    }

    public boolean isLoaded() {
        return activeSourceDataLine != null && totalAudioFrameCount > 0;
    }

    public boolean isPlaying() {
        return playbackIsActive;
    }

    public boolean hasMetronome() {
        return metronomeClickSynthesizer.isClickClipAvailable();
    }

    /** Load (and decode) a WAV file. Replaces any previously loaded audio. */
    public synchronized void loadAndDecodeWavFile(File wavFile) throws Exception {
        LOG.info("Loading audio: " + wavFile.getAbsolutePath());
        close();

        AudioInputStream in = AudioSystem.getAudioInputStream(wavFile);
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

        this.bytesPerAudioFrame = Math.max(1, target.getFrameSize());
        this.sampleRateInHz = target.getSampleRate();
        this.decodedPcmAudioBytes = in.readAllBytes();
        in.close();
        this.totalAudioFrameCount = decodedPcmAudioBytes.length / bytesPerAudioFrame;

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, target);
        SourceDataLine openedSourceDataLine = (SourceDataLine) AudioSystem.getLine(info);
        // A larger line buffer (~300 ms) absorbs GC pauses and EDT scheduling spikes
        // without underrunning. Reported position stays accurate regardless of buffer
        // size because it is derived from bufferSize - available() (queued bytes), not
        // from the write cursor alone — see pump() below.
        int desired = (int) (sampleRateInHz * 0.3) * bytesPerAudioFrame;
        int bufBytes = Math.max(bytesPerAudioFrame, desired);
        openedSourceDataLine.open(target, bufBytes);
        this.activeSourceDataLine = openedSourceDataLine;

        playbackIsRequested = false;
        playbackIsActive = false;
        pendingSeekTargetFrame = -1;
        nextWriteCursorFrame = 0;
        lastRenderedAudioFrame = 0;

        metronomeClickSynthesizer.initializeSynthesizedClickClip();
        audioPumpThreadIsRunning = true;
        audioPumpThread = new Thread(this::pump, "audio-pump");
        audioPumpThread.setDaemon(true);
        // Give the pump a priority edge so a busy EDT or GC helper cannot starve it
        // and drain the line buffer faster than it can be refilled.
        audioPumpThread.setPriority(Thread.MAX_PRIORITY);
        audioPumpThread.start();
        LOG.fine(String.format("Audio loaded: %.1fs, %d frames, %.0f Hz",
                totalAudioFrameCount / sampleRateInHz, totalAudioFrameCount, (double) sampleRateInHz));
    }

    private void pump() {
        final int chunkFrames = 1024;
        final int chunkBytes = chunkFrames * bytesPerAudioFrame;
        final SourceDataLine localSourceDataLine = activeSourceDataLine;
        while (audioPumpThreadIsRunning) {
            // Service a pending seek first.
            long s = pendingSeekTargetFrame;
            if (s >= 0) {
                localSourceDataLine.flush();
                nextWriteCursorFrame = Math.max(0, Math.min(s, totalAudioFrameCount));
                pendingSeekTargetFrame = -1;
                lastRenderedAudioFrame = nextWriteCursorFrame; // queue flushed; rendered == write cursor
            }

            if (playbackIsRequested) {
                if (nextWriteCursorFrame >= totalAudioFrameCount) {
                    // reached the end
                    playbackIsRequested = false;
                    playbackIsActive = false;
                    localSourceDataLine.stop();
                    lastRenderedAudioFrame = totalAudioFrameCount;
                    LOG.fine("Playback reached end");
                    if (playbackCompletionListener != null) {
                        playbackCompletionListener.onEnd();
                    }
                    continue;
                }
                if (!playbackIsActive) {
                    localSourceDataLine.start();
                    playbackIsActive = true;
                }
                long start = nextWriteCursorFrame * (long) bytesPerAudioFrame;
                int len = (int) Math.min(chunkBytes, decodedPcmAudioBytes.length - start);
                int written = localSourceDataLine.write(decodedPcmAudioBytes, (int) start, len); // blocking, no lock held
                nextWriteCursorFrame += written / bytesPerAudioFrame;
                // Compute how many bytes are still buffered inside the line and
                // publish the true rendered position — all from the pump thread so
                // the EDT never has to touch the line monitor.
                int queued = Math.max(0, localSourceDataLine.getBufferSize() - localSourceDataLine.available());
                lastRenderedAudioFrame = Math.max(0, nextWriteCursorFrame - queued / bytesPerAudioFrame);
            } else {
                if (playbackIsActive) {
                    localSourceDataLine.stop();
                    playbackIsActive = false;
                    lastRenderedAudioFrame = nextWriteCursorFrame; // line stopped; queue drained
                }
                synchronized (pumpWaitLock) {
                    try {
                        pumpWaitLock.wait(50); // woken early by play() / seekSeconds() / close()
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }
    }

    public void play() {
        if (!isLoaded()) {
            return;
        }
        if (nextWriteCursorFrame >= totalAudioFrameCount) {
            pendingSeekTargetFrame = 0;
        }
        playbackIsRequested = true;
        synchronized (pumpWaitLock) { pumpWaitLock.notifyAll(); }
    }

    public void pause() {
        playbackIsRequested = false;
    }

    public void stop() {
        playbackIsRequested = false;
        pendingSeekTargetFrame = 0;
    }

    public void togglePlay() {
        if (playbackIsActive) {
            pause();
        } else {
            play();
        }
    }

    public void seekSeconds(double seconds) {
        if (!isLoaded()) {
            return;
        }
        long f = (long) (seconds * sampleRateInHz);
        pendingSeekTargetFrame = Math.max(0, Math.min(f, totalAudioFrameCount));
        synchronized (pumpWaitLock) { pumpWaitLock.notifyAll(); }
    }

    /** Truly lock-free playback position: reads only volatile fields, never the line. */
    public double getPositionSeconds() {
        if (activeSourceDataLine == null) {
            return 0;
        }
        // A pending seek is reflected immediately so the UI feels snappy.
        long pending = pendingSeekTargetFrame;
        long f = pending >= 0 ? Math.max(0, Math.min(pending, totalAudioFrameCount)) : lastRenderedAudioFrame;
        return Math.max(0, Math.min(f, totalAudioFrameCount)) / (double) sampleRateInHz;
    }

    public double getDurationSeconds() {
        return totalAudioFrameCount / (double) sampleRateInHz;
    }

    // ---- metronome -------------------------------------------------------

    /** Fire one metronome click (non-blocking, overlaps playback). */
    public void playClick() {
        metronomeClickSynthesizer.triggerClickPlayback();
    }

    // ---- lifecycle -------------------------------------------------------

    public synchronized void close() {
        audioPumpThreadIsRunning = false;
        playbackIsRequested = false;
        playbackIsActive = false;
        synchronized (pumpWaitLock) { pumpWaitLock.notifyAll(); }
        Thread pumpThreadToStop = audioPumpThread;
        if (pumpThreadToStop != null) {
            pumpThreadToStop.interrupt();
            try {
                pumpThreadToStop.join(500);
            } catch (InterruptedException ignored) {
            }
            audioPumpThread = null;
        }
        SourceDataLine sourceDataLineToClose = activeSourceDataLine;
        if (sourceDataLineToClose != null) {
            try {
                sourceDataLineToClose.stop();
                sourceDataLineToClose.flush();
                sourceDataLineToClose.close();
            } catch (Exception ignored) {
            }
            activeSourceDataLine = null;
        }
        metronomeClickSynthesizer.closeClickClip();
        decodedPcmAudioBytes = new byte[0];
        totalAudioFrameCount = 0;
        nextWriteCursorFrame = 0;
        pendingSeekTargetFrame = -1;
        lastRenderedAudioFrame = 0;
    }
}
