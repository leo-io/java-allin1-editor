package com.audioeditor.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A single bar: an ordered list of {@link Beat}s. The first beat (when
 * present) is conventionally the downbeat ({@link Beat#isDownbeat()} ==
 * {@code true}). The bar's time span is derived from its beats — it is not a
 * stored field — so editing any beat keeps a bar's bounds consistent.
 */
public class Bar {
    private final List<Beat> beats;

    public Bar() {
        this.beats = new ArrayList<>();
    }

    public List<Beat> getBeats() {
        return beats;
    }

    public void addBeat(Beat beat) {
        beats.add(beat);
    }

    public void removeBeat(int index) {
        if (index >= 0 && index < beats.size()) {
            beats.remove(index);
        }
    }

    public void moveBeat(int from, int to) {
        if (from < 0 || from >= beats.size() || to < 0 || to >= beats.size() || from == to) {
            return;
        }
        Beat b = beats.remove(from);
        beats.add(to, b);
    }

    /** First beat's start, or 0 if the bar is empty. */
    public double getStartTime() {
        return beats.isEmpty() ? 0.0 : beats.get(0).getStart();
    }

    /** Last beat's end, or 0 if the bar is empty. */
    public double getEndTime() {
        return beats.isEmpty() ? 0.0 : beats.get(beats.size() - 1).getEnd();
    }

    public double getDuration() {
        return getEndTime() - getStartTime();
    }

    public Bar copy() {
        Bar copy = new Bar();
        for (Beat b : beats) {
            copy.addBeat(b.copy());
        }
        return copy;
    }

    @Override
    public String toString() {
        return String.format("Bar[%.3f-%.3f beats=%d]", getStartTime(), getEndTime(), beats.size());
    }
}