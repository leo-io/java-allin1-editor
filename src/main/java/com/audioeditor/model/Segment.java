package com.audioeditor.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A song segment: a labelled group of bars (e.g. intro / verse / chorus /
 * outro). The segment's time span is computed from its bars — it is not a
 * stored field — so edits to bar beat times keep the segment bounds consistent
 * automatically.
 */
public class Segment {
    private String label;
    private final List<Bar> bars;

    public Segment(String label) {
        this.label = label;
        this.bars = new ArrayList<>();
    }

    /**
     * Legacy constructor kept for clarity while callers transition to the bar
     * abstraction. The supplied {@code start}/{@code end} are ignored — the
     * bounds are always derived from the bars.
     */
    public Segment(double ignoredStart, double ignoredEnd, String label) {
        this(label);
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public List<Bar> getBars() {
        return bars;
    }

    public void addBar(Bar bar) {
        bars.add(bar);
    }

    public void removeBar(int index) {
        if (index >= 0 && index < bars.size()) {
            bars.remove(index);
        }
    }

    public void moveBar(int from, int to) {
        if (from < 0 || from >= bars.size() || to < 0 || to >= bars.size() || from == to) {
            return;
        }
        Bar b = bars.remove(from);
        bars.add(to, b);
    }

    /** Start of the first beat of the first bar, or 0 if the segment has no bars/beats. */
    public double getStart() {
        for (Bar bar : bars) {
            if (!bar.getBeats().isEmpty()) {
                return bar.getStartTime();
            }
        }
        return 0.0;
    }

    /** End of the last beat of the last bar, or 0 if the segment has no bars/beats. */
    public double getEnd() {
        for (int i = bars.size() - 1; i >= 0; i--) {
            if (!bars.get(i).getBeats().isEmpty()) {
                return bars.get(i).getEndTime();
            }
        }
        return 0.0;
    }

    public double getDuration() {
        return getEnd() - getStart();
    }

    public Segment copy() {
        Segment copy = new Segment(label);
        for (Bar bar : bars) {
            copy.addBar(bar.copy());
        }
        return copy;
    }

    @Override
    public String toString() {
        return String.format("Segment[%.2f-%.2f %s bars=%d]", getStart(), getEnd(), label, bars.size());
    }
}