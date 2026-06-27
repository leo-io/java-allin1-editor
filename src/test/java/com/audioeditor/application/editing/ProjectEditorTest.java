package com.audioeditor.application.editing;

import com.audioeditor.model.Bar;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectEditorTest {

    @Test
    void nestedCollectionsAreReadOnlyAndEditorMaintainsInvariants() {
        ProjectModel project = new ProjectModel();
        Segment segment = new Segment("verse");
        Bar bar = new Bar();
        Beat first = new Beat(true, 0, 1);
        Beat second = new Beat(false, 1, 2);
        bar.addBeat(first);
        bar.addBeat(second);
        segment.addBar(bar);
        project.addSegment(segment);

        assertThrows(UnsupportedOperationException.class,
                () -> project.getSegments().add(new Segment("invalid")));
        assertThrows(UnsupportedOperationException.class,
                () -> segment.getBars().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> bar.getBeats().remove(first));

        ProjectEditor editor = new ProjectEditor(project);
        editor.updateBeat(second, 0.75, 1.5, true);

        assertEquals(0.75, second.getStart(), 1e-9);
        assertEquals(first.getEnd(), second.getStart(), 1e-9);
        assertTrue(first.isDownbeat());
    }
}
