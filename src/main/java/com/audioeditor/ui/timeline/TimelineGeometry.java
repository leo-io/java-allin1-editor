package com.audioeditor.ui.timeline;

import com.audioeditor.model.Segment;

/** Pure layout and coordinate calculations for the Swing timeline. */
public final class TimelineGeometry {

    public static final int HEADER_HEIGHT_PIXELS = 22;
    public static final int ROW_SPACING_PIXELS = 2;

    private int rowHeightPixels;

    public TimelineGeometry(int rowHeightPixels) {
        setRowHeight(rowHeightPixels);
    }

    public void setRowHeight(int rowHeightPixels) {
        this.rowHeightPixels = Math.max(40, rowHeightPixels);
    }

    public int rowHeight() {
        return rowHeightPixels;
    }

    public int rowTop(int segmentIndex) {
        return segmentIndex * (rowHeightPixels + ROW_SPACING_PIXELS);
    }

    public int rowBottom(int segmentIndex) {
        return rowTop(segmentIndex) + rowHeightPixels;
    }

    public int headerBottom(int segmentIndex) {
        return rowTop(segmentIndex) + HEADER_HEIGHT_PIXELS;
    }

    public int downbeatZoneHeight() {
        return Math.max(16, (rowHeightPixels - HEADER_HEIGHT_PIXELS) / 3);
    }

    public int downbeatZoneBottom(int segmentIndex) {
        return headerBottom(segmentIndex) + downbeatZoneHeight();
    }

    public int hitSegmentRow(int y, int segmentCount) {
        int stride = rowHeightPixels + ROW_SPACING_PIXELS;
        int index = y / stride;
        return index >= 0 && index < segmentCount && y < rowBottom(index) ? index : -1;
    }

    public int contentWidth(Segment segment, int panelWidth, double maximumSegmentDuration) {
        return (int) Math.round(panelWidth * segment.getDuration() / maximumSegmentDuration);
    }

    public int timeToX(double seconds, Segment segment, int panelWidth, double maximumSegmentDuration) {
        double scale = panelWidth / maximumSegmentDuration;
        return (int) Math.round((seconds - segment.getStart()) * scale);
    }

    public double xToTime(int x, Segment segment, int panelWidth, double maximumSegmentDuration) {
        if (panelWidth <= 0) {
            return segment.getStart();
        }
        return segment.getStart() + x * maximumSegmentDuration / panelWidth;
    }
}
