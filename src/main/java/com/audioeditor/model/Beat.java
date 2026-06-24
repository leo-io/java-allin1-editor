package com.audioeditor.model;

/**
 * A single beat: its time in seconds and its position within the bar (1..N,
 * typically 1-4). The JSON stores these as two parallel arrays
 * ({@code beats} and {@code beat_positions}); we merge them into one editable
 * object and split them back on save.
 */
public class Beat {
    private double beatTimeInSeconds;
    private int beatPositionWithinBar;

    public Beat(double beatTimeInSeconds, int beatPositionWithinBar) {
        this.beatTimeInSeconds = beatTimeInSeconds;
        this.beatPositionWithinBar = beatPositionWithinBar;
    }

    public double getTime() {
        return beatTimeInSeconds;
    }

    public void setTime(double time) {
        this.beatTimeInSeconds = time;
    }

    public int getPosition() {
        return beatPositionWithinBar;
    }

    public void setPosition(int position) {
        this.beatPositionWithinBar = position;
    }

    public Beat copy() {
        return new Beat(beatTimeInSeconds, beatPositionWithinBar);
    }

    @Override
    public String toString() {
        return String.format("Beat[%.3f, pos=%d]", beatTimeInSeconds, beatPositionWithinBar);
    }
}
