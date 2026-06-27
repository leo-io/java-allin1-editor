package com.audioeditor.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Control;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.SourceDataLine;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PcmWavPlaybackEngine} state transitions using the
 * {@link AudioLineFactory} seam — no real audio device is needed.
 */
class PcmWavPlaybackEngineStateTest {

    private static final AudioFormat FORMAT =
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100f, 16, 2, 4, 44100f, false);

    private PcmWavPlaybackEngine engine;

    @AfterEach
    void tearDown() throws Exception {
        if (engine != null) engine.close();
    }

    @Test
    void notLoadedBeforeLoad() {
        engine = newEngine(new InstantLine(4096));
        assertFalse(engine.isLoaded());
        assertFalse(engine.isPlaying());
    }

    @Test
    void loadedAfterLoadCall() throws Exception {
        engine = newEngine(new InstantLine(4096));
        engine.load(Path.of("synthetic.wav"));
        assertTrue(engine.isLoaded());
        assertFalse(engine.isPlaying());
    }

    @Test
    void playDoesNotThrowWhenLoaded() throws Exception {
        engine = newEngine(new InstantLine(4096));
        engine.load(Path.of("synthetic.wav"));
        assertDoesNotThrow(() -> engine.play());
    }

    @Test
    void pauseDoesNotThrowWhenLoaded() throws Exception {
        engine = newEngine(new InstantLine(4096));
        engine.load(Path.of("synthetic.wav"));
        assertDoesNotThrow(() -> engine.pause());
    }

    @Test
    void stopDoesNotThrowWhenLoaded() throws Exception {
        engine = newEngine(new InstantLine(4096));
        engine.load(Path.of("synthetic.wav"));
        assertDoesNotThrow(() -> engine.stop());
    }

    @Test
    void seekUpdatesPositionImmediately() throws Exception {
        engine = newEngine(new InstantLine(4096));
        engine.load(Path.of("synthetic.wav"));
        engine.seekSeconds(0.5);
        // Pending seek is reported immediately by getPositionSeconds()
        assertTrue(engine.getPositionSeconds() >= 0.4 && engine.getPositionSeconds() <= 0.6,
                "Position should reflect pending seek, was " + engine.getPositionSeconds());
    }

    @Test
    void playingTransitionWithBlockingLine() throws Exception {
        // Use a line that blocks writes until released so we can inspect mid-play state.
        BlockingLine line = new BlockingLine(4096);
        line.blockWrites.set(true);
        engine = newEngine(line);
        engine.load(Path.of("synthetic.wav"));

        engine.play();
        Thread.sleep(30); // pump wakes up, sets playbackIsActive = true, then blocks in write()

        assertTrue(engine.isPlaying(), "Engine should be playing while pump is blocked in write()");

        line.blockWrites.set(false); // release pump to clean up
    }

    @Test
    void pauseTransitionWithBlockingLine() throws Exception {
        BlockingLine line = new BlockingLine(4096);
        line.blockWrites.set(true);
        engine = newEngine(line);
        engine.load(Path.of("synthetic.wav"));

        engine.play();
        Thread.sleep(30); // pump enters playing state and blocks in write()
        assertTrue(engine.isPlaying());

        line.blockWrites.set(false); // let pump finish current write
        engine.pause();
        Thread.sleep(100); // pump services the pause within its wait cycle
        assertFalse(engine.isPlaying());
    }

    @Test
    void metronomeMethodsDoNotThrow() throws Exception {
        engine = newEngine(new InstantLine(4096));
        engine.load(Path.of("synthetic.wav"));
        assertDoesNotThrow(() -> engine.setMetronomeEnabled(true));
        assertDoesNotThrow(() -> engine.setMetronomeBeatTimes(new double[]{0.5, 1.0, 1.5}));
        assertDoesNotThrow(() -> engine.setMetronomeEnabled(false));
    }

    @Test
    void playableRangesDoNotThrow() throws Exception {
        engine = newEngine(new InstantLine(4096));
        engine.load(Path.of("synthetic.wav"));
        assertDoesNotThrow(() -> engine.setPlayableRanges(List.of()));
        assertDoesNotThrow(() -> engine.setPlayableRanges(null));
    }

    @Test
    void notLoadedAfterClose() throws Exception {
        engine = newEngine(new InstantLine(4096));
        engine.load(Path.of("synthetic.wav"));
        assertTrue(engine.isLoaded());
        engine.close();
        assertFalse(engine.isLoaded());
        engine = null; // prevent double-close in tearDown
    }

    // ---- factory helpers ---------------------------------------------------

    private static PcmWavPlaybackEngine newEngine(SourceDataLine line) {
        WavDecoder stubDecoder = new WavDecoder() {
            @Override
            public DecodedPcmAudio decode(Path source) {
                // 10 seconds of silence at 44100 Hz, 16-bit stereo
                byte[] pcm = new byte[44100 * 10 * 4];
                return new DecodedPcmAudio(FORMAT, pcm);
            }
        };
        AudioLineFactory stubFactory = (fmt, bufBytes) -> line;
        return new PcmWavPlaybackEngine(stubDecoder, stubFactory);
    }

    // ---- stub SourceDataLine: write() returns immediately ------------------

    private static final class InstantLine extends AbstractNoOpLine {
        InstantLine(int bufferSize) { super(bufferSize); }

        @Override public int write(byte[] b, int off, int len) { return len; }
    }

    // ---- stub SourceDataLine: write() blocks while blockWrites is true -----

    private static final class BlockingLine extends AbstractNoOpLine {
        final AtomicBoolean blockWrites = new AtomicBoolean(false);

        BlockingLine(int bufferSize) { super(bufferSize); }

        @Override
        public int write(byte[] b, int off, int len) {
            while (blockWrites.get()) {
                try { Thread.sleep(5); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 0;
                }
            }
            return len;
        }
    }

    // ---- common no-op SourceDataLine base ----------------------------------

    private abstract static class AbstractNoOpLine implements SourceDataLine {
        private final int bufferSize;
        private volatile boolean open = false;

        AbstractNoOpLine(int bufferSize) { this.bufferSize = bufferSize; }

        @Override public abstract int write(byte[] b, int off, int len);
        @Override public void open(AudioFormat fmt) { open = true; }
        @Override public void open(AudioFormat fmt, int bufSize) { open = true; }
        @Override public void open() { open = true; }
        @Override public void close() { open = false; }
        @Override public boolean isOpen() { return open; }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void drain() { }
        @Override public void flush() { }
        @Override public boolean isRunning() { return false; }
        @Override public boolean isActive() { return false; }
        @Override public AudioFormat getFormat() { return FORMAT; }
        @Override public int getBufferSize() { return bufferSize; }
        @Override public int available() { return bufferSize; }
        @Override public int getFramePosition() { return 0; }
        @Override public long getLongFramePosition() { return 0L; }
        @Override public long getMicrosecondPosition() { return 0L; }
        @Override public float getLevel() { return 0f; }
        @Override public Line.Info getLineInfo() { return new Line.Info(SourceDataLine.class); }
        @Override public Control[] getControls() { return new Control[0]; }
        @Override public boolean isControlSupported(Control.Type t) { return false; }
        @Override public Control getControl(Control.Type t) {
            throw new IllegalArgumentException("No control: " + t);
        }
        @Override public void addLineListener(LineListener l) { }
        @Override public void removeLineListener(LineListener l) { }
    }
}
