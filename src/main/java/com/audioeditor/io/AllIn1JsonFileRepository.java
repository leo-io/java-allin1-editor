package com.audioeditor.io;

import com.audioeditor.model.Bar;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Loads and saves the analysis JSON in the new nested format (segments → bars → beats),
 * preserving any unmodelled top-level keys for faithful round-tripping.
 *
 * <p>Exposed as a singleton {@link #INSTANCE} implementing
 * {@link MusicAnalysisFileRepository} so callers depend on the interface, not
 * on Jackson.
 */
public final class AllIn1JsonFileRepository implements MusicAnalysisFileRepository {

    public static final AllIn1JsonFileRepository INSTANCE = new AllIn1JsonFileRepository();

    private static final Logger LOG = Logger.getLogger(AllIn1JsonFileRepository.class.getName());

    /** Keys this editor models explicitly; everything else is preserved as-is. */
    private static final Set<String> EXPLICITLY_MODELLED_JSON_KEYS = Set.of(
            "path", "bpm", "segments");

    private static final ObjectMapper JSON_OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private AllIn1JsonFileRepository() {
    }

    @Override
    public ProjectModel loadFromFile(File analysisJsonFile) throws IOException {
        LOG.info("Loading: " + analysisJsonFile.getAbsolutePath());
        JsonNode root = JSON_OBJECT_MAPPER.readTree(analysisJsonFile);
        ProjectModel model = new ProjectModel();

        if (root.has("beats") || root.has("downbeats") || root.has("beat_positions")) {
            throw new IOException("Unsupported legacy JSON format: expected segments[].bars[].beats[] only");
        }

        if (root.hasNonNull("path")) {
            model.setAudioPath(root.get("path").asText());
        }
        if (root.hasNonNull("bpm")) {
            model.setBpm(root.get("bpm").asDouble());
        }

        JsonNode segments = root.get("segments");
        if (segments != null && segments.isArray()) {
            for (JsonNode segNode : segments) {
                String label = segNode.path("label").asText("");
                Segment seg = new Segment(label);

                JsonNode bars = segNode.get("bars");
                if (bars == null || !bars.isArray()) {
                    throw new IOException("Unsupported segment JSON format: each segment must contain a bars array");
                }
                for (JsonNode barNode : bars) {
                    Bar bar = new Bar();

                    JsonNode beats = barNode.get("beats");
                    if (beats == null || !beats.isArray()) {
                        throw new IOException("Unsupported bar JSON format: each bar must contain a beats array");
                    }
                    for (JsonNode beatNode : beats) {
                        boolean isDownbeat = beatNode.path("isDownbeat").asBoolean();
                        double start = beatNode.path("start").asDouble();
                        double end = beatNode.path("end").asDouble();
                        bar.addBeat(new Beat(isDownbeat, start, end));
                    }

                    seg.addBar(bar);
                }

                model.addSegment(seg);
            }
        }

        // Preserve unknown top-level keys.
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            if (!EXPLICITLY_MODELLED_JSON_KEYS.contains(e.getKey())) {
                model.getExtraFields().put(e.getKey(), e.getValue());
            }
        }

        model.setDirty(false);
        int totalBeats = model.getAllBeatsFlat().size();
        int totalBars = model.getSegments().stream().mapToInt(s -> s.getBars().size()).sum();
        LOG.fine(String.format("Loaded: segments=%d bars=%d beats=%d",
                model.getSegments().size(), totalBars, totalBeats));
        return model;
    }

    @Override
    public void saveToFile(ProjectModel projectModel, File targetFile) throws IOException {
        LOG.info("Saving: " + targetFile.getAbsolutePath());
        ObjectNode root = JSON_OBJECT_MAPPER.createObjectNode();

        root.put("path", projectModel.getAudioPath());

        // Emit BPM as an integer when it is whole (matches analyzer output).
        double bpm = projectModel.getBpm();
        if (bpm == Math.rint(bpm) && !Double.isInfinite(bpm)) {
            root.put("bpm", (long) bpm);
        } else {
            root.put("bpm", bpm);
        }

        ArrayNode segArr = root.putArray("segments");
        for (Segment s : projectModel.getSegments()) {
            ObjectNode segNode = segArr.addObject();
            segNode.put("label", s.getLabel());

            ArrayNode barsArr = segNode.putArray("bars");
            for (Bar bar : s.getBars()) {
                ObjectNode barNode = barsArr.addObject();

                ArrayNode beatsArr = barNode.putArray("beats");
                for (Beat b : bar.getBeats()) {
                    ObjectNode beatNode = beatsArr.addObject();
                    beatNode.put("isDownbeat", b.isDownbeat());
                    beatNode.put("start", roundToMs(b.getStart()));
                    beatNode.put("end", roundToMs(b.getEnd()));
                }
            }
        }

        // Re-emit any preserved unknown keys.
        for (Map.Entry<String, JsonNode> e : projectModel.getExtraFields().entrySet()) {
            root.set(e.getKey(), e.getValue());
        }

        JSON_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(targetFile, root);
        projectModel.setDirty(false);
        LOG.fine("Saved successfully");
    }

    /** Round to millisecond precision to avoid float noise in the output. */
    private double roundToMs(double valueInSeconds) {
        return Math.round(valueInSeconds * 1000.0) / 1000.0;
    }
}
