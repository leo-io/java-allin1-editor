package com.audioeditor.application.project;

import com.audioeditor.application.EditorSession;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.port.persistence.MusicAnalysisRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Application workflow for loading and saving the current project document. */
public final class ProjectFileController {

    private final EditorSession session;
    private final MusicAnalysisRepository repository;

    public ProjectFileController(EditorSession session, MusicAnalysisRepository repository) {
        this.session = Objects.requireNonNull(session, "session");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public ProjectModel load(Path source) throws IOException {
        return repository.load(source);
    }

    /** Activates a decoded project; Swing calls this on its event-dispatch thread. */
    public void activate(ProjectModel loaded, Path source) {
        session.replaceWith(loaded, source);
    }

    public void save(Path target) throws IOException {
        repository.save(session.project(), target);
        session.markSaved(target);
    }

    public Path currentFile() {
        return session.currentFile();
    }

    public boolean hasUnsavedChanges() {
        return session.isDirty();
    }
}
