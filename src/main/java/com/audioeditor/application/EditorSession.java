package com.audioeditor.application;

import com.audioeditor.model.ProjectModel;

import java.nio.file.Path;
import java.util.Objects;

/**
 * State of the current editing session. File identity and dirty tracking are
 * application concerns and deliberately live outside the music domain model.
 */
public final class EditorSession implements ProjectModel.ProjectChangeListener {

    private final ProjectModel project;
    private Path currentFile;
    private boolean dirty;

    public EditorSession(ProjectModel project) {
        this.project = Objects.requireNonNull(project, "project");
        project.addProjectChangeListener(this);
    }

    public ProjectModel project() {
        return project;
    }

    public Path currentFile() {
        return currentFile;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void replaceWith(ProjectModel loadedProject, Path sourceFile) {
        project.copyFrom(Objects.requireNonNull(loadedProject, "loadedProject"));
        currentFile = sourceFile;
        dirty = false;
    }

    public void markSaved(Path savedFile) {
        currentFile = savedFile;
        dirty = false;
    }

    @Override
    public void modelChanged() {
        dirty = true;
    }
}
