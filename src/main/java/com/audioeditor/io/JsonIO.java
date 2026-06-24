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

/**
 * Loads and saves the analysis JSON, mapping the parallel {@code beats} /
 * {@code beat_positions} arrays to a single editable beat list and back, and
 * preserving any unmodelled top-level keys for faithful round-tripping.
 */
public final class JsonIO {

    /** Keys this editor models explicitly; everything else is preserved as-is. */
    private static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
            "path", "bpm", "beats", "beat_positions", "downbeats", "segments");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonIO() {
    }

    public static ProjectModel load(File file) throws IOException {
        JsonNode root = MAPPER.readTree(file);
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
            if (!KNOWN_KEYS.contains(e.getKey())) {
                model.getExtraFields().put(e.getKey(), e.getValue());
            }
        }

        model.setDirty(false);
        return model;
    }

    public static void save(ProjectModel model, File file) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();

        root.put("path", model.getAudioPath());

        // Emit BPM as an integer when it is whole (matches analyzer output).
        double bpm = model.getBpm();
        if (bpm == Math.rint(bpm) && !Double.isInfinite(bpm)) {
            root.put("bpm", (long) bpm);
        } else {
            root.put("bpm", bpm);
        }

        ArrayNode beatsArr = root.putArray("beats");
        ArrayNode posArr = root.putArray("beat_positions");
        for (Beat b : model.getBeats()) {
            beatsArr.add(round(b.getTime()));
            posArr.add(b.getPosition());
        }

        ArrayNode downArr = root.putArray("downbeats");
        for (Double d : model.getDownbeats()) {
            downArr.add(round(d));
        }

        ArrayNode segArr = root.putArray("segments");
        for (Segment s : model.getSegments()) {
            ObjectNode o = segArr.addObject();
            o.put("start", round(s.getStart()));
            o.put("end", round(s.getEnd()));
            o.put("label", s.getLabel());
        }

        // Re-emit any preserved unknown keys.
        for (Map.Entry<String, JsonNode> e : model.getExtraFields().entrySet()) {
            root.set(e.getKey(), e.getValue());
        }

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, root);
        model.setDirty(false);
    }

    /** Round to millisecond precision to avoid float noise in the output. */
    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
