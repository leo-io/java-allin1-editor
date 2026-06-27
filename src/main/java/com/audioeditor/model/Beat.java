package com.audioeditor.model;

import java.util.UUID;

/**
 * A single beat inside a bar. Stores whether it is the bar's downbeat
 * (first beat) plus its time range {@code [start, end]} in seconds.
 *
 * <p>The {@code end} of one beat is expected to match the {@code start} of
 * the next beat in the same bar so the bar's beats are contiguous.
 */
public class Beat {
    private final UUID id;
    private boolean downbeat;
    private double startTime;
    private double endTime;

    public Beat(boolean downbeat, double startTime, double endTime) {
        this(UUID.randomUUID(), downbeat, startTime, endTime);
    }

    private Beat(UUID id, boolean downbeat, double startTime, double endTime) {
        this.id = id;
        this.downbeat = downbeat;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public UUID getId() {
        return id;
    }

    public boolean isDownbeat() {
        return downbeat;
    }

    public void setDownbeat(boolean downbeat) {
        this.downbeat = downbeat;
    }

    public double getStart() {
        return startTime;
    }

    public void setStart(double startTime) {
        this.startTime = startTime;
    }

    public double getEnd() {
        return endTime;
    }

    public void setEnd(double endTime) {
        this.endTime = endTime;
    }

    /** Position within the bar, 1-based; the downbeat is always position 1. */
    public int getPosition() {
        return downbeat ? 1 : 0;
    }

    public Beat copy() {
        return new Beat(downbeat, startTime, endTime);
    }

    @Override
    public String toString() {
        return String.format("Beat[%s %.3f-%.3f]", downbeat ? "D" : "b", startTime, endTime);
    }
}
