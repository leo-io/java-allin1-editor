package com.audioeditor.port.persistence;

import com.audioeditor.model.ProjectModel;

import java.io.IOException;
import java.nio.file.Path;

/** Persistence port for an editable music-analysis document. */
public interface MusicAnalysisRepository {

    ProjectModel load(Path analysisFile) throws IOException;

    void save(ProjectModel project, Path targetFile) throws IOException;
}
