package com.audioeditor.audio;

import com.audioeditor.domain.analysis.TimeRange;
import com.audioeditor.port.audio.AudioPlayer;
import com.audioeditor.port.audio.AudioPlayerException;

import javax.sound.sampled.SourceDataLine;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * WAV playback engine built on {@code javax.sound.sampled}.
 *
 * <p>The file is decoded once into in-memory 16-bit PCM. A dedicated pump thread
 * owns the single {@link SourceDataLine} and is the <em>only</em> thread that
 * calls the blocking {@code write}/{@code flush} methods, so the UI thread never
 * blocks on audio I/O. Play / pause / stop / seek are expressed as volatile
 * requests that the pump thread services; the current position is computed
 * lock-free from the write cursor minus the bytes still queued in the line's
 * buffer, which tracks actual playback closely.
 *
 * <p>The metronome click is <b>mixed into this same stream</b> by the pump thread
 * at sample-accurate beat positions, rather than played on a second line. This
 * removes the extra mixer line that glitched the primary line on the Windows
 * {@code DirectAudioDevice} mixer, and makes click timing exact instead of tied
 * to a ~30&nbsp;ms EDT tick.
 */
public class PcmWavPlaybackEngine implements AudioPlayer {

    private static final Logger LOG = Logger.getLogger(PcmWavPlaybackEngine.class.getName());

    private final WavDecoder wavDecoder;
    private final AudioLineFactory audioLineFactory;

    public interface PlaybackCompletionListener {
        void onEnd();
    }

    private byte[] decodedPcmAudioBytes = new byte[0];
    private int bytesPerAudioFrame = 1;
    private int audioChannelCount = 1;
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
    // Wall-clock (nanoTime) at which lastRenderedAudioFrame was published. Lets the EDT
    // interpolate a smooth playhead between the now-large pump writes without reading
    // the line. Paired with lastRenderedAudioFrame; read both as a best-effort snapshot.
    private volatile long lastRenderTimestampNanos = 0;

    // Half-open playable frame ranges [s0,e0, s1,e1, ...], sorted and merged, that
    // playback is restricted to. The pump skips any frame outside these ranges and
    // stitches the kept ranges together, so deleting a segment in the editor removes
    // that audio from playback (an edit-decision list). null means "no restriction"
    // (the whole decoded file is playable); an empty array means no audio is kept.
    private volatile long[] playableFrameRanges = null;
    private volatile long nextWriteCursorPlayableFrame = 0; // next stitched-frame offset handed to the line
    private volatile long lastRenderedPlayableFrame = 0;    // stitched-frame offset actually rendered

    // Monitor used to wake the pump thread from its idle wait when play/seek is requested.
    private final Object pumpWaitLock = new Object();
    private PlaybackCompletionListener playbackCompletionListener;

    // ---- metronome (mixed in by the pump) --------------------------------
    private volatile boolean metronomeEnabled = false;
    // Sorted beat positions in frames. Immutable; swapped wholesale from the EDT.
    private volatile long[] beatFramePositions = new long[0];
    private byte[] clickPcmBytes = new byte[0];             // click in playback format
    private int clickFrameCount = 0;
    // Reusable per-chunk mix scratch buffer (sized at load) — avoids per-chunk allocation.
    private byte[] mixWorkBuffer = new byte[0];
    // Pump-owned scheduling state (touched only by the pump thread).
    private long[] beatFramesSeenByPump = null;             // detects an EDT array swap
    private int nextBeatIndex = 0;                          // next beat >= write cursor
    private int activeClickPosFrame = 0;                    // read offset into the click
    private int activeClickRemainingFrames = 0;             // 0 == no click sounding

    public PcmWavPlaybackEngine() {
        this(new WavDecoder(), new JavaSoundLineFactory());
    }

    public PcmWavPlaybackEngine(WavDecoder wavDecoder, AudioLineFactory audioLineFactory) {
        this.wavDecoder = wavDecoder;
        this.audioLineFactory = audioLineFactory;
    }

    public void setPlaybackCompletionListener(PlaybackCompletionListener listener) {
        this.playbackCompletionListener = listener;
    }

    @Override
    public void onPlaybackCompleted(Runnable listener) {
        this.playbackCompletionListener = listener == null ? null : listener::run;
    }

    public boolean isLoaded() {
        return activeSourceDataLine != null && totalAudioFrameCount > 0;
    }

    public boolean isPlaying() {
        return playbackIsActive;
    }

    public boolean hasMetronome() {
        return clickFrameCount > 0;
    }

    /** Load (and decode) a WAV file. Replaces any previously loaded audio. */
    @Override
    public void load(Path audioFile) throws AudioPlayerException {
        try {
            loadAndDecodeWavFile(audioFile.toFile());
        } catch (Exception exception) {
            throw new AudioPlayerException("Unable to load audio: " + audioFile, exception);
        }
    }

    /** Compatibility entry point retained for existing integrations. */
    public synchronized void loadAndDecodeWavFile(File wavFile) throws Exception {
        LOG.info("Loading audio: " + wavFile.getAbsolutePath());
        close();

        DecodedPcmAudio decoded = wavDecoder.decode(wavFile.toPath());
        this.bytesPerAudioFrame = decoded.bytesPerFrame();
        this.audioChannelCount = decoded.channels();
        this.sampleRateInHz = decoded.sampleRate();
        this.decodedPcmAudioBytes = decoded.bytes();
        this.totalAudioFrameCount = decoded.frameCount();

        // A larger line buffer (~500 ms) absorbs GC pauses, EDT scheduling spikes and
        // the occasional slow blocking write without underrunning. Reported position
        // stays accurate regardless of buffer size because it is derived from
        // bufferSize - available() (queued bytes), not from the write cursor alone —
        // see pump() below.
        int desired = (int) (sampleRateInHz * 0.5) * bytesPerAudioFrame;
        int bufBytes = Math.max(bytesPerAudioFrame, desired);
        SourceDataLine openedSourceDataLine = audioLineFactory.open(decoded.format(), bufBytes);
        this.activeSourceDataLine = openedSourceDataLine;

        // Build the click in the playback format and the reusable mix scratch buffer.
        this.clickPcmBytes = MetronomeClickSynthesizer.synthesizeClick(sampleRateInHz, audioChannelCount);
        this.clickFrameCount = clickPcmBytes.length / bytesPerAudioFrame;
        this.mixWorkBuffer = new byte[PUMP_CHUNK_FRAMES * bytesPerAudioFrame];

        playbackIsRequested = false;
        playbackIsActive = false;
        pendingSeekTargetFrame = -1;
        nextWriteCursorFrame = 0;
        lastRenderedAudioFrame = 0;
        lastRenderTimestampNanos = System.nanoTime();
        playableFrameRanges = null;
        nextWriteCursorPlayableFrame = 0;
        lastRenderedPlayableFrame = 0;
        beatFramePositions = new long[0];
        beatFramesSeenByPump = null;
        nextBeatIndex = 0;
        activeClickPosFrame = 0;
        activeClickRemainingFrames = 0;

        audioPumpThreadIsRunning = true;
        audioPumpThread = new Thread(this::pump, "audio-pump");
        audioPumpThread.setDaemon(true);
        // Give the pump a priority edge so a busy EDT or GC helper cannot starve it
        // and drain the line buffer faster than it can be refilled.
        audioPumpThread.setPriority(Thread.MAX_PRIORITY);
        audioPumpThread.start();
        LOG.fine(String.format("Audio loaded: %.1fs, %d frames, %.0f Hz, %d ch",
                totalAudioFrameCount / sampleRateInHz, totalAudioFrameCount, (double) sampleRateInHz, audioChannelCount));
    }

    // Frames handed to SourceDataLine.write() per iteration. This MUST stay large:
    // the Windows DirectAudioDevice has a high fixed per-write() latency (~15-30 ms
    // measured), so small chunks throttle delivery well below real time and the line
    // underruns continuously — at 1024 frames throughput collapsed to ~0.6x real time,
    // making playback stutter to near-silence. 8192 frames (~186 ms @ 44.1 kHz)
    // amortizes that fixed cost so the blocking write paces cleanly to real time.
    private static final int PUMP_CHUNK_FRAMES = 8192;

    private void pump() {
        final int chunkFrames = PUMP_CHUNK_FRAMES;
        final int chunkBytes = chunkFrames * bytesPerAudioFrame;
        final SourceDataLine localSourceDataLine = activeSourceDataLine;
        while (audioPumpThreadIsRunning) {
            // Service a pending seek first.
            long s = pendingSeekTargetFrame;
            if (s >= 0) {
                localSourceDataLine.flush();
                long[] ranges = playableFrameRanges;
                nextWriteCursorFrame = Math.max(0, Math.min(s, totalAudioFrameCount));
                nextWriteCursorPlayableFrame = sourceFrameToPlayableOffset(nextWriteCursorFrame, ranges);
                pendingSeekTargetFrame = -1;
                lastRenderedAudioFrame = nextWriteCursorFrame; // queue flushed; rendered == write cursor
                lastRenderedPlayableFrame = nextWriteCursorPlayableFrame;
                resyncBeatPointer(nextWriteCursorFrame);       // re-aim the metronome
                activeClickRemainingFrames = 0;                // drop any click crossing the seek
            }

            if (playbackIsRequested) {
                long[] ranges = playableFrameRanges;       // immutable snapshot
                long startFrame = nextWriteCursorFrame;
                long intervalEnd = playableIntervalEndContaining(startFrame, ranges);
                if (intervalEnd < 0) {
                    // The cursor is in a deleted gap (or past the last kept range).
                    // Jump straight to the next kept range, or stop if none remain.
                    long nextStart = nextPlayableStartAfter(startFrame, ranges);
                    if (nextStart < 0) {
                        if (playbackIsActive && publishRenderedPositionFromLine(localSourceDataLine, ranges) > 0) {
                            sleepBrieflyWhileDraining();
                            continue;
                        }
                        playbackIsRequested = false;
                        playbackIsActive = false;
                        localSourceDataLine.stop();
                        lastRenderedAudioFrame = startFrame;
                        LOG.fine("Playback reached end of kept audio");
                        if (playbackCompletionListener != null) {
                            playbackCompletionListener.onEnd();
                        }
                        continue;
                    }
                    nextWriteCursorFrame = nextStart;
                    nextWriteCursorPlayableFrame = sourceFrameToPlayableOffset(nextStart, ranges);
                    if (!playbackIsActive || publishRenderedPositionFromLine(localSourceDataLine, ranges) == 0) {
                        lastRenderedAudioFrame = nextStart;
                        lastRenderedPlayableFrame = nextWriteCursorPlayableFrame;
                        lastRenderTimestampNanos = System.nanoTime();
                    }
                    resyncBeatPointer(nextStart);              // re-aim the metronome over the skip
                    activeClickRemainingFrames = 0;            // drop any click crossing the gap
                    continue;
                }
                if (!playbackIsActive) {
                    localSourceDataLine.start();
                    lastRenderTimestampNanos = System.nanoTime();
                    playbackIsActive = true;
                }
                // Never write past the current kept range's end — the next loop turn
                // lands in the gap and jumps to the following range.
                long chunkLimitFrame = Math.min(intervalEnd, totalAudioFrameCount);
                int framesThisChunk = (int) Math.min(chunkFrames, chunkLimitFrame - startFrame);
                int len = framesThisChunk * bytesPerAudioFrame;
                // Mix metronome clicks in if any sound during this chunk; otherwise
                // write straight from the decoded buffer (zero-copy fast path).
                byte[] src;
                int srcOffset;
                if (prepareMixedChunk(startFrame, framesThisChunk)) {
                    src = mixWorkBuffer;
                    srcOffset = 0;
                } else {
                    src = decodedPcmAudioBytes;
                    srcOffset = (int) (startFrame * bytesPerAudioFrame);
                }
                int written = localSourceDataLine.write(src, srcOffset, len); // blocking, no lock held
                int writtenFrames = written / bytesPerAudioFrame;
                nextWriteCursorFrame += writtenFrames;
                nextWriteCursorPlayableFrame += writtenFrames;
                // Compute how many bytes are still buffered inside the line and
                // publish the true rendered position — all from the pump thread so
                // the EDT never has to touch the line monitor.
                publishRenderedPositionFromLine(localSourceDataLine, ranges);
            } else {
                if (playbackIsActive) {
                    localSourceDataLine.stop();
                    playbackIsActive = false;
                    lastRenderedAudioFrame = nextWriteCursorFrame; // line stopped; queue drained
                    lastRenderedPlayableFrame = nextWriteCursorPlayableFrame;
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

    /**
     * Publish the source-frame position currently reaching the speakers. The line
     * queue can span a deleted gap, so queue math is done in stitched playable
     * frames and then mapped back to source frames for the UI playhead.
     *
     * @return queued audio frames still buffered in the line
     */
    private int publishRenderedPositionFromLine(SourceDataLine sourceDataLine, long[] ranges) {
        int queuedBytes = Math.max(0, sourceDataLine.getBufferSize() - sourceDataLine.available());
        int queuedFrames = queuedBytes / bytesPerAudioFrame;
        long renderedPlayableFrame = Math.max(0, nextWriteCursorPlayableFrame - queuedFrames);
        lastRenderedPlayableFrame = renderedPlayableFrame;
        lastRenderedAudioFrame = playableOffsetToSourceFrame(renderedPlayableFrame, ranges);
        lastRenderTimestampNanos = System.nanoTime();
        return queuedFrames;
    }

    private void sleepBrieflyWhileDraining() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            audioPumpThreadIsRunning = false;
        }
    }

    /**
     * Prepare the chunk {@code [startFrame, startFrame+framesThisChunk)} into
     * {@link #mixWorkBuffer} with any metronome clicks added, returning {@code true}
     * when mixing happened (caller should write the work buffer) or {@code false}
     * to take the zero-copy fast path (no click sounds in this chunk).
     *
     * <p>Runs only on the pump thread.
     */
    private boolean prepareMixedChunk(long startFrame, int framesThisChunk) {
        long[] frames = beatFramePositions; // snapshot the (immutable) array
        if (frames != beatFramesSeenByPump) {
            // The EDT swapped the beat list (load / edit); re-aim the pointer.
            resyncBeatPointer(startFrame);
            beatFramesSeenByPump = frames;
        } else {
            while (nextBeatIndex < frames.length && frames[nextBeatIndex] < startFrame) {
                nextBeatIndex++;
            }
        }

        boolean clickOngoing = activeClickRemainingFrames > 0;
        boolean beatInChunk = metronomeEnabled
                && nextBeatIndex < frames.length
                && frames[nextBeatIndex] < startFrame + framesThisChunk;

        if (!metronomeEnabled || (!clickOngoing && !beatInChunk)) {
            // Nothing to mix. (A click already sounding still finishes even if the
            // checkbox was just turned off — clickOngoing covers that.)
            if (!metronomeEnabled) {
                activeClickRemainingFrames = 0;
            }
            return false;
        }

        int len = framesThisChunk * bytesPerAudioFrame;
        System.arraycopy(decodedPcmAudioBytes, (int) (startFrame * bytesPerAudioFrame), mixWorkBuffer, 0, len);

        // Finish a click carried over from the previous chunk.
        if (activeClickRemainingFrames > 0) {
            mixClickVoice(0, framesThisChunk);
        }
        // (Re)trigger clicks for every beat that starts within this chunk.
        if (metronomeEnabled) {
            while (nextBeatIndex < frames.length && frames[nextBeatIndex] < startFrame + framesThisChunk) {
                int offsetInChunk = (int) (frames[nextBeatIndex] - startFrame);
                if (offsetInChunk < 0) {
                    offsetInChunk = 0;
                }
                activeClickPosFrame = 0;
                activeClickRemainingFrames = clickFrameCount;
                mixClickVoice(offsetInChunk, framesThisChunk);
                nextBeatIndex++;
            }
        }
        return true;
    }

    /**
     * Add the active click voice into {@link #mixWorkBuffer} starting at
     * {@code startFrameInChunk}, advancing the voice and saturating to 16-bit.
     */
    private void mixClickVoice(int startFrameInChunk, int framesThisChunk) {
        int n = Math.min(activeClickRemainingFrames, framesThisChunk - startFrameInChunk);
        Pcm16Mixer.add(mixWorkBuffer, startFrameInChunk,
                clickPcmBytes, activeClickPosFrame, n, audioChannelCount);
        activeClickPosFrame += n;
        activeClickRemainingFrames -= n;
    }

    /** Re-aim {@link #nextBeatIndex} at the first beat &gt;= {@code frame}. Pump thread only. */
    private void resyncBeatPointer(long frame) {
        long[] frames = beatFramePositions;
        int i = Arrays.binarySearch(frames, frame);
        nextBeatIndex = i >= 0 ? i : -(i + 1);
    }

    public void play() {
        if (!isLoaded()) {
            return;
        }
        long[] ranges = playableFrameRanges;
        if (ranges != null && ranges.length == 0) {
            playbackIsRequested = false;
            playbackIsActive = false;
            return;
        }
        // If the cursor sits at/after the end of all kept audio, restart from the
        // first kept range so Play always resumes something audible.
        if (playableIntervalEndContaining(nextWriteCursorFrame, ranges) < 0
                && nextPlayableStartAfter(nextWriteCursorFrame, ranges) < 0) {
            pendingSeekTargetFrame = firstPlayableStart(ranges);
        }
        playbackIsRequested = true;
        synchronized (pumpWaitLock) { pumpWaitLock.notifyAll(); }
    }

    public void pause() {
        playbackIsRequested = false;
    }

    public void stop() {
        playbackIsRequested = false;
        pendingSeekTargetFrame = Math.max(0, firstPlayableStart(playableFrameRanges));
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
        if (pending >= 0) {
            return Math.max(0, Math.min(pending, totalAudioFrameCount)) / (double) sampleRateInHz;
        }
        long f = lastRenderedAudioFrame;
        // While actively playing, interpolate forward from the last pump publish using
        // wall-clock elapsed time so the playhead stays smooth between the large pump
        // writes. Clamp to what has actually been handed to the line (nextWriteCursorFrame)
        // so we never report past the audio that exists.
        if (playbackIsActive) {
            long elapsedNanos = System.nanoTime() - lastRenderTimestampNanos;
            if (elapsedNanos > 0) {
                long playable = lastRenderedPlayableFrame
                        + (long) (elapsedNanos * sampleRateInHz / 1_000_000_000.0);
                playable = Math.min(playable, nextWriteCursorPlayableFrame);
                f = playableOffsetToSourceFrame(playable, playableFrameRanges);
            }
        }
        return Math.max(0, Math.min(f, totalAudioFrameCount)) / (double) sampleRateInHz;
    }

    public double getDurationSeconds() {
        return totalAudioFrameCount / (double) sampleRateInHz;
    }

    // ---- playable ranges (edit-decision list) ----------------------------

    /**
     * Restrict playback to the given {@code [start, end]} second ranges (typically
     * the editor's surviving segments). Ranges are converted to frames, sorted,
     * clamped to the audio, and overlapping/adjacent ones merged; the pump skips
     * everything outside them and stitches the kept ranges together. Passing
     * {@code null} lifts the restriction (the whole file plays); passing an empty
     * array restricts playback to no audio.
     * Callable from the EDT during playback — the pump picks up the new array on
     * its next chunk and skips into a kept range if the cursor was left in a gap.
     */
    public void setPlayableTimeRangesSeconds(double[] startEndPairsSeconds) {
        playableFrameRanges = PlayableFrameRanges.normalize(
                startEndPairsSeconds, sampleRateInHz, totalAudioFrameCount);
    }

    /**
     * End frame (exclusive) of the kept range containing {@code frame}, or -1 if
     * {@code frame} is in a gap / past the last range. With no ranges set the whole
     * file is playable, so this returns {@link #totalAudioFrameCount} while in range.
     */
    private long playableIntervalEndContaining(long frame, long[] ranges) {
        return PlayableFrameRanges.intervalEndContaining(frame, ranges, totalAudioFrameCount);
    }

    /** Start frame of the first kept range at or after {@code frame}, or -1 if none. */
    private long nextPlayableStartAfter(long frame, long[] ranges) {
        return PlayableFrameRanges.nextStartAtOrAfter(frame, ranges, totalAudioFrameCount);
    }

    /** Start frame of the first kept range (0 when unrestricted). */
    private long firstPlayableStart(long[] ranges) {
        return PlayableFrameRanges.firstStart(ranges);
    }

    @Override
    public void setPlayableRanges(List<TimeRange> ranges) {
        if (ranges == null) {
            setPlayableTimeRangesSeconds(null);
            return;
        }
        double[] pairs = new double[ranges.size() * 2];
        int cursor = 0;
        for (TimeRange range : ranges) {
            pairs[cursor++] = range.startSeconds();
            pairs[cursor++] = range.endSeconds();
        }
        setPlayableTimeRangesSeconds(pairs);
    }

    private long sourceFrameToPlayableOffset(long sourceFrame, long[] ranges) {
        return PlayableFrameRanges.sourceToPlayableOffset(sourceFrame, ranges, totalAudioFrameCount);
    }

    private long playableOffsetToSourceFrame(long playableOffset, long[] ranges) {
        return PlayableFrameRanges.playableOffsetToSource(playableOffset, ranges, totalAudioFrameCount);
    }

    // ---- metronome -------------------------------------------------------

    /** Enable/disable the in-stream metronome click. Cheap, callable from the EDT. */
    public void setMetronomeEnabled(boolean enabled) {
        this.metronomeEnabled = enabled;
    }

    /**
     * Publish the beats (in seconds) the metronome should click on. Converted to
     * frame positions, sorted, and swapped in wholesale; the pump re-aims itself
     * on the next chunk. Callable from the EDT during playback.
     */
    public void setMetronomeBeatTimes(double[] beatTimesSeconds) {
        if (beatTimesSeconds == null || beatTimesSeconds.length == 0) {
            beatFramePositions = new long[0];
            return;
        }
        long[] frames = new long[beatTimesSeconds.length];
        for (int i = 0; i < beatTimesSeconds.length; i++) {
            frames[i] = Math.max(0, Math.round(beatTimesSeconds[i] * sampleRateInHz));
        }
        Arrays.sort(frames);
        beatFramePositions = frames;
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
        decodedPcmAudioBytes = new byte[0];
        totalAudioFrameCount = 0;
        nextWriteCursorFrame = 0;
        pendingSeekTargetFrame = -1;
        lastRenderedAudioFrame = 0;
        playableFrameRanges = null;
        nextWriteCursorPlayableFrame = 0;
        lastRenderedPlayableFrame = 0;
        beatFramePositions = new long[0];
        beatFramesSeenByPump = null;
        nextBeatIndex = 0;
        activeClickPosFrame = 0;
        activeClickRemainingFrames = 0;
        clickPcmBytes = new byte[0];
        clickFrameCount = 0;
        mixWorkBuffer = new byte[0];
    }
}
