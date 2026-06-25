package com.audioeditor;

import com.audioeditor.model.Bar;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectModelSegmentEditingTest {

    @Test
    void renameSegmentUpdatesLabelVocabulary() {
        ProjectModel model = new ProjectModel();
        model.addSegment(segment("verse", bar(0)));

        assertTrue(model.renameSegment(0, "prechorus"));

        assertEquals("prechorus", model.getSegments().get(0).getLabel());
        assertTrue(model.getLabelVocabulary().contains("prechorus"));
    }

    @Test
    void pasteSegmentCopiesInsertsDeepCopiesAfterSelectedIndex() {
        ProjectModel model = new ProjectModel();
        model.addSegment(segment("a", bar(0)));
        model.addSegment(segment("b", bar(4)));

        List<Segment> copies = model.copySegmentsAtIndices(List.of(0));
        int insertedAt = model.pasteSegmentCopiesAfter(0, copies);

        assertEquals(1, insertedAt);
        assertEquals(3, model.getSegments().size());
        assertEquals("a", model.getSegments().get(1).getLabel());
        assertNotSame(model.getSegments().get(0), model.getSegments().get(1));
        assertNotSame(model.getSegments().get(0).getBars().get(0), model.getSegments().get(1).getBars().get(0));
    }

    @Test
    void mergeRequiresContiguousSegmentsAndKeepsFirstLabel() {
        ProjectModel model = new ProjectModel();
        model.addSegment(segment("a", bar(0)));
        model.addSegment(segment("b", bar(4)));
        model.addSegment(segment("c", bar(8)));

        assertFalse(model.canMergeAdjacentSegments(List.of(0, 2)));
        assertEquals(-1, model.mergeAdjacentSegments(List.of(0, 2)));

        assertTrue(model.canMergeAdjacentSegments(List.of(0, 1)));
        int mergedIndex = model.mergeAdjacentSegments(List.of(0, 1));

        assertEquals(0, mergedIndex);
        assertEquals(2, model.getSegments().size());
        assertEquals("a", model.getSegments().get(0).getLabel());
        assertEquals(2, model.getSegments().get(0).getBars().size());
        assertEquals("c", model.getSegments().get(1).getLabel());
    }

    @Test
    void splitUsesNearestInternalBarBoundaryAndKeepsLabels() {
        ProjectModel model = new ProjectModel();
        model.addSegment(segment("verse", bar(0), bar(4), bar(8), bar(12)));

        int newIndex = model.splitSegmentAtNearestBarBoundary(0, 6.9);

        assertEquals(1, newIndex);
        assertEquals(2, model.getSegments().size());
        assertEquals("verse", model.getSegments().get(0).getLabel());
        assertEquals("verse", model.getSegments().get(1).getLabel());
        assertEquals(2, model.getSegments().get(0).getBars().size());
        assertEquals(2, model.getSegments().get(1).getBars().size());
        assertEquals(8.0, model.getSegments().get(1).getStart(), 1e-9);
    }

    @Test
    void splitRejectsUnsplittableSegments() {
        ProjectModel model = new ProjectModel();
        model.addSegment(segment("one", bar(0)));

        assertFalse(model.canSplitSegmentAtNearestBarBoundary(0));
        assertEquals(-1, model.splitSegmentAtNearestBarBoundary(0, 0));
    }

    private Segment segment(String label, Bar... bars) {
        Segment segment = new Segment(label);
        for (Bar bar : bars) {
            segment.addBar(bar);
        }
        return segment;
    }

    private Bar bar(double start) {
        Bar bar = new Bar();
        for (int i = 0; i < 4; i++) {
            bar.addBeat(new Beat(i == 0, start + i, start + i + 1));
        }
        return bar;
    }
}
