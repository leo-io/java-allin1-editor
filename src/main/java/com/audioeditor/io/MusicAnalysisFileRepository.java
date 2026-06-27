package com.audioeditor.io;

import com.audioeditor.model.ProjectModel;
import com.audioeditor.port.persistence.MusicAnalysisRepository;

import java.io.File;
import java.io.IOException;

/**
 * Decouples the UI from the concrete JSON serialisation library (Jackson).
 *
 * <p>Implementations load and save the allin1-style music-analysis document
 * behind a stable, technology-neutral contract so the UI depends only on this
 * interface, not on Jackson types.
 */
@Deprecated(forRemoval = false)
public interface MusicAnalysisFileRepository extends MusicAnalysisRepository {

    default ProjectModel loadFromFile(File analysisJsonFile) throws IOException {
        return load(analysisJsonFile.toPath());
    }

    default void saveToFile(ProjectModel projectModel, File targetFile) throws IOException {
        save(projectModel, targetFile.toPath());
    }
}
