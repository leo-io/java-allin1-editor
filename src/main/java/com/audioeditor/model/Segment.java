package com.audioeditor.model;

/**
 * A song segment: a labelled time range (e.g. intro / verse / chorus / outro).
 */
public class Segment {
    private double start;
    private double end;
    private String label;

    public Segment(double start, double end, String label) {
        this.start = start;
        this.end = end;
        this.label = label;
    }

    public double getStart() {
        return start;
    }

    public void setStart(double start) {
        this.start = start;
    }

    public double getEnd() {
        return end;
    }

    public void setEnd(double end) {
        this.end = end;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public double getDuration() {
        return end - start;
    }

    public Segment copy() {
        return new Segment(start, end, label);
    }

    @Override
    public String toString() {
        return String.format("Segment[%.2f-%.2f %s]", start, end, label);
    }
}
