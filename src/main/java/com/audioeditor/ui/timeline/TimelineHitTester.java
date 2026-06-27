package com.audioeditor.ui.timeline;

import com.audioeditor.model.Bar;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;

/** Pure hit detection for beats, bars, and segment rows in the timeline. */
public final class TimelineHitTester {

    static final int HIT_TEST_TOLERANCE_PIXELS = 5;

    private final ProjectModel model;
    private final TimelineGeometry geometry;
    private final TimelineSelectionLookup selectionLookup;

    public TimelineHitTester(ProjectModel model, TimelineGeometry geometry,
                      TimelineSelectionLookup selectionLookup) {
        this.model = model;
        this.geometry = geometry;
        this.selectionLookup = selectionLookup;
    }

    /** Closest beat within x tolerance in the given segment row; returns flat beat index or -1. */
    public int hitBeatInSegment(int x, int segmentIndex, int panelWidth, double maxDuration) {
        if (segmentIndex < 0) return -1;
        Segment s = model.getSegments().get(segmentIndex);
        int best = -1;
        int bestD = HIT_TEST_TOLERANCE_PIXELS + 1;

        int flatBeatIndex = 0;
        for (Segment seg : model.getSegments()) {
            if (seg == s) break;
            for (Bar bar : seg.getBars()) flatBeatIndex += bar.getBeats().size();
        }

        int i = flatBeatIndex;
        for (Bar bar : s.getBars()) {
            for (Beat b : bar.getBeats()) {
                int d = Math.abs(geometry.timeToX(b.getStart(), s, panelWidth, maxDuration) - x);
                if (d < bestD) { bestD = d; best = i; }
                i++;
            }
        }
        return best;
    }

    /** Closest bar (downbeat marker) within x tolerance in the given segment row; returns flat bar index or -1. */
    public int hitDownbeatInSegment(int x, int segmentIndex, int panelWidth, double maxDuration) {
        if (segmentIndex < 0) return -1;
        Segment s = model.getSegments().get(segmentIndex);
        int best = -1;
        int bestD = HIT_TEST_TOLERANCE_PIXELS + 1;

        int barIndex = selectionLookup.firstBarIndex(s);
        for (Bar bar : s.getBars()) {
            if (!bar.getBeats().isEmpty() && bar.getBeats().get(0).isDownbeat()) {
                double t = bar.getBeats().get(0).getStart();
                int d = Math.abs(geometry.timeToX(t, s, panelWidth, maxDuration) - x);
                if (d < bestD) { bestD = d; best = barIndex; }
            }
            barIndex++;
        }
        return best;
    }
}
