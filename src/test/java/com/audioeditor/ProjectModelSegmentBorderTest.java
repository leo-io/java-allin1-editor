package com.audioeditor;

import com.audioeditor.io.AllIn1JsonFileRepository;
import com.audioeditor.model.Bar;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectModelSegmentBorderTest {

    @Test
    void shrinkSegmentPushesSplitEdgeBarsToNeighbors() {
        ProjectModel model = new ProjectModel();
        Segment previous = segment("previous", bar(0, 4), bar(4, 1));
        Segment selected = segment("selected", bar(5, 3), bar(8, 4), bar(12, 4), bar(16, 2));
        Segment next = segment("next", bar(18, 2), bar(20, 4));
        model.addSegment(previous);
        model.addSegment(selected);
        model.addSegment(next);

        assertTrue(model.shrinkSegmentToFullBarBorders(1));

        assertEquals(2, previous.getBars().size());
        assertEquals(4, previous.getBars().get(1).getBeats().size());
        assertEquals(2, selected.getBars().size());
        assertEquals(4, selected.getBars().get(0).getBeats().size());
        assertEquals(4, selected.getBars().get(1).getBeats().size());
        assertEquals(2, next.getBars().size());
        assertEquals(4, next.getBars().get(0).getBeats().size());
        assertTrue(selected.getBars().get(0).getBeats().get(0).isDownbeat());
    }

    @Test
    void expandSegmentPullsAdjacentSplitFragmentsIntoSelectedSegment() {
        ProjectModel model = new ProjectModel();
        Segment previous = segment("previous", bar(0, 4), bar(4, 1));
        Segment selected = segment("selected", bar(5, 3), bar(8, 4), bar(12, 4), bar(16, 2));
        Segment next = segment("next", bar(18, 2), bar(20, 4));
        model.addSegment(previous);
        model.addSegment(selected);
        model.addSegment(next);

        assertTrue(model.expandSegmentToFullBarBorders(1));

        assertEquals(1, previous.getBars().size());
        assertEquals(4, selected.getBars().size());
        assertEquals(4, selected.getBars().get(0).getBeats().size());
        assertEquals(4, selected.getBars().get(3).getBeats().size());
        assertEquals(1, next.getBars().size());
        assertTrue(selected.getBars().get(0).getBeats().get(0).isDownbeat());
    }

    @Test
    void fullBarMoveRefusesOddResultAndAllowsEvenResult() {
        ProjectModel model = new ProjectModel();
        Segment first = segment("first", bar(0, 4), bar(4, 4));
        Segment second = segment("second", bar(8, 4), bar(12, 4), bar(16, 4), bar(20, 4));
        model.addSegment(first);
        model.addSegment(second);

        assertFalse(model.moveFirstBarsFromNextSegment(0, 1));
        assertEquals(2, first.getBars().size());
        assertEquals(4, second.getBars().size());

        assertTrue(model.moveFirstBarsFromNextSegment(0, 2));
        assertEquals(4, first.getBars().size());
        assertEquals(2, model.getSegments().get(1).getBars().size());
    }

    @Test
    void oddPairCanMoveOneFullBarAndPersistsToJson() throws Exception {
        ProjectModel model = new ProjectModel();
        model.setAudioPath("C:/audio/song.wav");
        model.setBpm(122);
        Segment first = segment("first", bar(0, 4), bar(4, 4), bar(8, 4));
        Segment second = segment("second", bar(12, 4), bar(16, 4), bar(20, 4));
        model.addSegment(first);
        model.addSegment(second);

        assertTrue(model.moveFirstBarsFromNextSegment(0, 1));
        assertEquals(4, first.getBars().size());
        assertEquals(2, second.getBars().size());

        File file = Files.createTempFile("segment-border-fix", ".json").toFile();
        file.deleteOnExit();
        AllIn1JsonFileRepository.INSTANCE.saveToFile(model, file);
        ProjectModel loaded = AllIn1JsonFileRepository.INSTANCE.loadFromFile(file);

        assertEquals(4, loaded.getSegments().get(0).getBars().size());
        assertEquals(2, loaded.getSegments().get(1).getBars().size());
        assertEquals(4, loaded.getSegments().get(0).getBars().get(3).getBeats().size());
    }

    private Segment segment(String label, Bar... bars) {
        Segment segment = new Segment(label);
        for (Bar bar : bars) {
            segment.addBar(bar);
        }
        return segment;
    }

    private Bar bar(double start, int beatCount) {
        Bar bar = new Bar();
        for (int i = 0; i < beatCount; i++) {
            bar.addBeat(new Beat(i == 0, start + i, start + i + 1));
        }
        return bar;
    }
}
