package com.audioeditor.model;

/**
 * A single beat: its time in seconds and its position within the bar (1..N,
 * typically 1-4). The JSON stores these as two parallel arrays
 * ({@code beats} and {@code beat_positions}); we merge them into one editable
 * object and split them back on save.
 */
public class Beat {
    private double time;
    private int position;

    public Beat(double time, int position) {
        this.time = time;
        this.position = position;
    }

    public double getTime() {
        return time;
    }

    public void setTime(double time) {
        this.time = time;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public Beat copy() {
        return new Beat(time, position);
    }

    @Override
    public String toString() {
        return String.format("Beat[%.3f, pos=%d]", time, position);
    }
}
