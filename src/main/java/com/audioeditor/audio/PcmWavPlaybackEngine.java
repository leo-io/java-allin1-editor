package com.audioeditor.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.File;
import java.util.Arrays;
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
public class PcmWavPlaybackEngine {

    private static final Logger LOG = Logger.getLogger(PcmWavPlaybackEngine.class.getName());

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
    // that audio from playback (an edit-decision list). An empty array means "no
    // restriction" — the whole decoded file is playable.
    private volatile long[] playableFrameRanges = new long[0];

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
        return clickFrameCount > 0;
    }

    /** Load (and decode) a WAV file. Replaces any previously loaded audio. */
    public synchronized void loadAndDecodeWavFile(File wavFile) throws Exception {
        LOG.info("Loading audio: " + wavFile.getAbsolutePath());
        close();

        AudioInputStream in = AudioSystem.getAudioInputStream(wavFile);
        AudioFormat base = in.getFormat();
        // Always decode to 16-bit signed little-endian PCM so the in-stream click
        // mixer can assume a uniform sample layout (channels * 2 bytes per frame).
        boolean already16BitSigned = base.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                && base.getSampleSizeInBits() == 16
                && !base.isBigEndian();
        AudioFormat target = base;
        if (!already16BitSigned) {
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
        this.audioChannelCount = Math.max(1, target.getChannels());
        this.sampleRateInHz = target.getSampleRate();
        this.decodedPcmAudioBytes = in.readAllBytes();
        in.close();
        this.totalAudioFrameCount = decodedPcmAudioBytes.length / bytesPerAudioFrame;

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, target);
        SourceDataLine openedSourceDataLine = (SourceDataLine) AudioSystem.getLine(info);
        // A larger line buffer (~500 ms) absorbs GC pauses, EDT scheduling spikes and
        // the occasional slow blocking write without underrunning. Reported position
        // stays accurate regardless of buffer size because it is derived from
        // bufferSize - available() (queued bytes), not from the write cursor alone —
        // see pump() below.
        int desired = (int) (sampleRateInHz * 0.5) * bytesPerAudioFrame;
        int bufBytes = Math.max(bytesPerAudioFrame, desired);
        openedSourceDataLine.open(target, bufBytes);
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
        playableFrameRanges = new long[0];
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
                nextWriteCursorFrame = Math.max(0, Math.min(s, totalAudioFrameCount));
                pendingSeekTargetFrame = -1;
                lastRenderedAudioFrame = nextWriteCursorFrame; // queue flushed; rendered == write cursor
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
                    localSourceDataLine.flush();
                    nextWriteCursorFrame = nextStart;
                    lastRenderedAudioFrame = nextStart;        // queue flushed; rendered == cursor
                    lastRenderTimestampNanos = System.nanoTime();
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
                nextWriteCursorFrame += written / bytesPerAudioFrame;
                // Compute how many bytes are still buffered inside the line and
                // publish the true rendered position — all from the pump thread so
                // the EDT never has to touch the line monitor.
                int queued = Math.max(0, localSourceDataLine.getBufferSize() - localSourceDataLine.available());
                lastRenderedAudioFrame = Math.max(0, nextWriteCursorFrame - queued / bytesPerAudioFrame);
                lastRenderTimestampNanos = System.nanoTime();
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
        int ch = audioChannelCount;
        for (int f = 0; f < n; f++) {
            int dstBase = (startFrameInChunk + f) * bytesPerAudioFrame;
            int clkBase = (activeClickPosFrame + f) * bytesPerAudioFrame;
            for (int c = 0; c < ch; c++) {
                int di = dstBase + c * 2;
                int ci = clkBase + c * 2;
                int mixed = (short) ((mixWorkBuffer[di] & 0xff) | (mixWorkBuffer[di + 1] << 8))
                        + (short) ((clickPcmBytes[ci] & 0xff) | (clickPcmBytes[ci + 1] << 8));
                if (mixed > Short.MAX_VALUE) {
                    mixed = Short.MAX_VALUE;
                } else if (mixed < Short.MIN_VALUE) {
                    mixed = Short.MIN_VALUE;
                }
                mixWorkBuffer[di] = (byte) (mixed & 0xff);
                mixWorkBuffer[di + 1] = (byte) ((mixed >> 8) & 0xff);
            }
        }
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
        pendingSeekTargetFrame = firstPlayableStart(playableFrameRanges);
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
                f += (long) (elapsedNanos * sampleRateInHz / 1_000_000_000.0);
                f = Math.min(f, nextWriteCursorFrame);
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
     * {@code null} or an empty array lifts the restriction (the whole file plays).
     * Callable from the EDT during playback — the pump picks up the new array on
     * its next chunk and skips into a kept range if the cursor was left in a gap.
     */
    public void setPlayableTimeRangesSeconds(double[] startEndPairsSeconds) {
        if (startEndPairsSeconds == null || startEndPairsSeconds.length < 2) {
            playableFrameRanges = new long[0];
            return;
        }
        long total = totalAudioFrameCount > 0 ? totalAudioFrameCount : Long.MAX_VALUE;
        // Collect valid [start,end) frame pairs.
        long[][] pairs = new long[startEndPairsSeconds.length / 2][2];
        int count = 0;
        for (int i = 0; i + 1 < startEndPairsSeconds.length; i += 2) {
            long s = Math.max(0, Math.round(startEndPairsSeconds[i] * sampleRateInHz));
            long e = Math.min(total, Math.round(startEndPairsSeconds[i + 1] * sampleRateInHz));
            if (e > s) {
                pairs[count][0] = s;
                pairs[count][1] = e;
                count++;
            }
        }
        if (count == 0) {
            playableFrameRanges = new long[0];
            return;
        }
        java.util.Arrays.sort(pairs, 0, count, (a, b) -> Long.compare(a[0], b[0]));
        // Merge overlapping / touching ranges.
        long[] merged = new long[count * 2];
        int m = 0;
        long curStart = pairs[0][0];
        long curEnd = pairs[0][1];
        for (int i = 1; i < count; i++) {
            if (pairs[i][0] <= curEnd) {
                curEnd = Math.max(curEnd, pairs[i][1]);
            } else {
                merged[m++] = curStart;
                merged[m++] = curEnd;
                curStart = pairs[i][0];
                curEnd = pairs[i][1];
            }
        }
        merged[m++] = curStart;
        merged[m++] = curEnd;
        playableFrameRanges = java.util.Arrays.copyOf(merged, m);
    }

    /**
     * End frame (exclusive) of the kept range containing {@code frame}, or -1 if
     * {@code frame} is in a gap / past the last range. With no ranges set the whole
     * file is playable, so this returns {@link #totalAudioFrameCount} while in range.
     */
    private long playableIntervalEndContaining(long frame, long[] ranges) {
        if (ranges.length == 0) {
            return frame < totalAudioFrameCount ? totalAudioFrameCount : -1;
        }
        for (int i = 0; i < ranges.length; i += 2) {
            if (frame >= ranges[i] && frame < ranges[i + 1]) {
                return ranges[i + 1];
            }
        }
        return -1;
    }

    /** Start frame of the first kept range at or after {@code frame}, or -1 if none. */
    private long nextPlayableStartAfter(long frame, long[] ranges) {
        if (ranges.length == 0) {
            return frame < totalAudioFrameCount ? Math.max(0, frame) : -1;
        }
        for (int i = 0; i < ranges.length; i += 2) {
            if (ranges[i] >= frame) {
                return ranges[i];
            }
        }
        return -1;
    }

    /** Start frame of the first kept range (0 when unrestricted). */
    private long firstPlayableStart(long[] ranges) {
        return ranges.length == 0 ? 0 : ranges[0];
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
        playableFrameRanges = new long[0];
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
