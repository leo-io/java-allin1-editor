package com.audioeditor.domain.analysis;

/** Immutable half-open audio time range, measured in seconds. */
public record TimeRange(double startSeconds, double endSeconds) {

    public TimeRange {
        if (!Double.isFinite(startSeconds) || !Double.isFinite(endSeconds)) {
            throw new IllegalArgumentException("Time range values must be finite");
        }
        if (startSeconds < 0 || endSeconds < startSeconds) {
            throw new IllegalArgumentException("Invalid time range: " + startSeconds + ".." + endSeconds);
        }
    }
}
