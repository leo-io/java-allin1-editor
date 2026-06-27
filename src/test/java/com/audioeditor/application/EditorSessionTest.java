package com.audioeditor.application;

import com.audioeditor.model.ProjectModel;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorSessionTest {

    @Test
    void dirtyStateBelongsToSessionAndResetsOnOpenAndSave() {
        ProjectModel project = new ProjectModel();
        EditorSession session = new EditorSession(project);

        project.setBpm(120);
        assertTrue(session.isDirty());

        session.markSaved(Path.of("song.json"));
        assertFalse(session.isDirty());

        ProjectModel loaded = new ProjectModel();
        loaded.setBpm(90);
        session.replaceWith(loaded, Path.of("loaded.json"));
        assertFalse(session.isDirty());
    }
}
