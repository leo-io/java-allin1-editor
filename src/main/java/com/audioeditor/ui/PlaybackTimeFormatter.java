package com.audioeditor.ui;

/**
 * Stateless formatter that renders a playback position in seconds as
 * {@code M:SS.T} (e.g. {@code 1:23.4}). Extracted from {@code MainFrame} so the
 * formatting rule lives in exactly one place and can be reused.
 */
public final class PlaybackTimeFormatter {

    private PlaybackTimeFormatter() {
    }

    public static String formatSecondsAsMinutesAndSeconds(double positionInSeconds) {
        if (positionInSeconds < 0) {
            positionInSeconds = 0;
        }
        int minutes = (int) (positionInSeconds / 60);
        double remainingSeconds = positionInSeconds - minutes * 60;
        return String.format("%d:%04.1f", minutes, remainingSeconds);
    }
}
