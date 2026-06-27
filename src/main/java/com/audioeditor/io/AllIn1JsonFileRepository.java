package com.audioeditor.io;

import com.audioeditor.model.ProjectModel;
import com.audioeditor.port.persistence.MusicAnalysisRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;

/** Jackson file adapter for the all-in-1 analysis format. */
public final class AllIn1JsonFileRepository implements MusicAnalysisRepository {

    private static final Logger LOG = Logger.getLogger(AllIn1JsonFileRepository.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Compatibility singleton; application bootstrap uses constructor injection. */
    public static final AllIn1JsonFileRepository INSTANCE = new AllIn1JsonFileRepository();

    private final AllIn1JsonMapper mapper;

    public AllIn1JsonFileRepository() {
        this.mapper = new AllIn1JsonMapper(JSON);
    }

    @Override
    public ProjectModel load(Path analysisFile) throws IOException {
        File file = analysisFile.toFile();
        LOG.info("Loading: " + file.getAbsolutePath());
        ProjectModel project = mapper.fromJson(JSON.readTree(file));
        int totalBeats = project.getAllBeatsFlat().size();
        int totalBars = project.getAllBarsFlat().size();
        LOG.fine(() -> "Loaded: segments=" + project.getSegments().size()
                + " bars=" + totalBars + " beats=" + totalBeats);
        return project;
    }

    /** Compatibility bridge for callers using the former File-based API. */
    public ProjectModel loadFromFile(File analysisFile) throws IOException {
        return load(analysisFile.toPath());
    }

    @Override
    public void save(ProjectModel project, Path targetPath) throws IOException {
        File target = targetPath.toFile();
        LOG.info("Saving: " + target.getAbsolutePath());
        JSON.writerWithDefaultPrettyPrinter().writeValue(target, mapper.toJson(project));
        LOG.fine("Saved successfully");
    }

    /** Compatibility bridge for callers using the former File-based API. */
    public void saveToFile(ProjectModel project, File targetFile) throws IOException {
        save(project, targetFile.toPath());
    }
}
