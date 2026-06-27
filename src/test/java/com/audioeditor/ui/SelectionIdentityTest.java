package com.audioeditor.ui;

import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SelectionIdentityTest {

    @Test
    void selectedEntitySurvivesReorderingByStableIdentity() {
        ProjectModel project = new ProjectModel();
        Segment selected = new Segment("selected");
        project.addSegment(selected);
        project.addSegment(new Segment("other"));
        SelectionModel selection = new SelectionModel();
        selection.bind(project);

        selection.selectSegment(0);
        project.moveSegment(0, 1);

        assertEquals(selected.getId(), selection.getSelectedItemId());
        assertEquals(1, selection.getSelectedItemIndex());
    }
}
