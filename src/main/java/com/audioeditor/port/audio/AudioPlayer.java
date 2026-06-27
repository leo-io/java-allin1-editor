package com.audioeditor.port.audio;

import com.audioeditor.domain.analysis.TimeRange;

import java.nio.file.Path;
import java.util.List;

/**
 * Application-facing audio transport. Implementations own decoding, device I/O,
 * and their realtime thread; callers interact only with transport-level values.
 */
public interface AudioPlayer extends AutoCloseable {

    void load(Path audioFile) throws AudioPlayerException;

    boolean isLoaded();

    boolean isPlaying();

    void play();

    void pause();

    void stop();

    default void togglePlay() {
        if (isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    void seekSeconds(double seconds);

    double getPositionSeconds();

    double getDurationSeconds();

    void setPlayableRanges(List<TimeRange> ranges);

    void setMetronomeEnabled(boolean enabled);

    void setMetronomeBeatTimes(double[] beatTimesSeconds);

    void onPlaybackCompleted(Runnable listener);

    @Override
    void close();
}
