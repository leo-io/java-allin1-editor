package com.audioeditor.model;

/**
 * A song segment: a labelled time range (e.g. intro / verse / chorus / outro).
 */
public class Segment {
    private double segmentStartTimeInSeconds;
    private double segmentEndTimeInSeconds;
    private String segmentStructuralLabel;

    public Segment(double segmentStartTimeInSeconds, double segmentEndTimeInSeconds, String segmentStructuralLabel) {
        this.segmentStartTimeInSeconds = segmentStartTimeInSeconds;
        this.segmentEndTimeInSeconds = segmentEndTimeInSeconds;
        this.segmentStructuralLabel = segmentStructuralLabel;
    }

    public double getStart() {
        return segmentStartTimeInSeconds;
    }

    public void setStart(double start) {
        this.segmentStartTimeInSeconds = start;
    }

    public double getEnd() {
        return segmentEndTimeInSeconds;
    }

    public void setEnd(double end) {
        this.segmentEndTimeInSeconds = end;
    }

    public String getLabel() {
        return segmentStructuralLabel;
    }

    public void setLabel(String label) {
        this.segmentStructuralLabel = label;
    }

    public double getDuration() {
        return segmentEndTimeInSeconds - segmentStartTimeInSeconds;
    }

    public Segment copy() {
        return new Segment(segmentStartTimeInSeconds, segmentEndTimeInSeconds, segmentStructuralLabel);
    }

    @Override
    public String toString() {
        return String.format("Segment[%.2f-%.2f %s]",
                segmentStartTimeInSeconds, segmentEndTimeInSeconds, segmentStructuralLabel);
    }
}
