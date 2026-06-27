package com.audioeditor.ui.timeline;

import com.audioeditor.model.Bar;
import com.audioeditor.model.Beat;
import com.audioeditor.model.Segment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimelineGeometryTest {

    @Test
    void mapsRowsAndGlobalTimeScale() {
        TimelineGeometry geometry = new TimelineGeometry(120);
        Segment segment = new Segment("verse");
        Bar bar = new Bar();
        bar.addBeat(new Beat(true, 10, 20));
        segment.addBar(bar);

        assertEquals(122, geometry.rowTop(1));
        assertEquals(-1, geometry.hitSegmentRow(120, 2));
        assertEquals(500, geometry.contentWidth(segment, 1000, 20));
        assertEquals(250, geometry.timeToX(15, segment, 1000, 20));
        assertEquals(15, geometry.xToTime(250, segment, 1000, 20), 1e-9);
    }
}
