package com.audioeditor.io;

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
 * Loads and saves the analysis JSON, mapping the parallel {@code beats} /
 * {@code beat_positions} arrays to a single editable beat list and back, and
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
            "path", "bpm", "beats", "beat_positions", "downbeats", "segments");

    private static final ObjectMapper JSON_OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private AllIn1JsonFileRepository() {
    }

    @Override
    public ProjectModel loadFromFile(File analysisJsonFile) throws IOException {
        LOG.info("Loading: " + analysisJsonFile.getAbsolutePath());
        JsonNode root = JSON_OBJECT_MAPPER.readTree(analysisJsonFile);
        ProjectModel model = new ProjectModel();

        if (root.hasNonNull("path")) {
            model.setAudioPath(root.get("path").asText());
        }
        if (root.hasNonNull("bpm")) {
            model.setBpm(root.get("bpm").asDouble());
        }

        JsonNode beats = root.get("beats");
        JsonNode positions = root.get("beat_positions");
        if (beats != null && beats.isArray()) {
            for (int i = 0; i < beats.size(); i++) {
                double t = beats.get(i).asDouble();
                int pos = (positions != null && positions.isArray() && i < positions.size())
                        ? positions.get(i).asInt() : 1;
                model.getBeats().add(new Beat(t, pos));
            }
        }

        JsonNode downbeats = root.get("downbeats");
        if (downbeats != null && downbeats.isArray()) {
            for (JsonNode n : downbeats) {
                model.getDownbeats().add(n.asDouble());
            }
        }

        JsonNode segments = root.get("segments");
        if (segments != null && segments.isArray()) {
            for (JsonNode n : segments) {
                double start = n.path("start").asDouble();
                double end = n.path("end").asDouble();
                String label = n.path("label").asText("");
                Segment seg = new Segment(start, end, label);
                model.getSegments().add(seg);
                model.rememberLabel(label);
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
        LOG.fine(String.format("Loaded: beats=%d downbeats=%d segments=%d",
                model.getBeats().size(), model.getDownbeats().size(), model.getSegments().size()));
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

        ArrayNode beatsArr = root.putArray("beats");
        ArrayNode posArr = root.putArray("beat_positions");
        for (Beat b : projectModel.getBeats()) {
            beatsArr.add(roundToMillisecondPrecision(b.getTime()));
            posArr.add(b.getPosition());
        }

        ArrayNode downArr = root.putArray("downbeats");
        for (Double d : projectModel.getDownbeats()) {
            downArr.add(roundToMillisecondPrecision(d));
        }

        ArrayNode segArr = root.putArray("segments");
        for (Segment s : projectModel.getSegments()) {
            ObjectNode o = segArr.addObject();
            o.put("start", roundToMillisecondPrecision(s.getStart()));
            o.put("end", roundToMillisecondPrecision(s.getEnd()));
            o.put("label", s.getLabel());
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
    private double roundToMillisecondPrecision(double valueInSeconds) {
        return Math.round(valueInSeconds * 1000.0) / 1000.0;
    }
}
