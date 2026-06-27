package com.audioeditor.io;

import com.audioeditor.model.Bar;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Maps the all-in-1 JSON schema to and from the Jackson-free domain model. */
final class AllIn1JsonMapper {

    private static final Set<String> MODELLED_KEYS = Set.of("path", "bpm", "segments");

    private final ObjectMapper objectMapper;

    AllIn1JsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ProjectModel fromJson(JsonNode root) throws IOException {
        if (root.has("beats") || root.has("downbeats") || root.has("beat_positions")) {
            throw new IOException("Unsupported legacy JSON format: expected segments[].bars[].beats[] only");
        }
        ProjectModel project = new ProjectModel();
        if (root.hasNonNull("path")) {
            project.setAudioPath(root.get("path").asText());
        }
        if (root.hasNonNull("bpm")) {
            project.setBpm(root.get("bpm").asDouble());
        }
        JsonNode segments = root.get("segments");
        if (segments != null && segments.isArray()) {
            for (JsonNode segmentNode : segments) {
                Segment segment = new Segment(segmentNode.path("label").asText(""));
                JsonNode bars = requireArray(segmentNode, "bars", "each segment");
                for (JsonNode barNode : bars) {
                    Bar bar = new Bar();
                    JsonNode beats = requireArray(barNode, "beats", "each bar");
                    for (JsonNode beatNode : beats) {
                        bar.addBeat(new Beat(
                                beatNode.path("isDownbeat").asBoolean(),
                                beatNode.path("start").asDouble(),
                                beatNode.path("end").asDouble()));
                    }
                    segment.addBar(bar);
                }
                project.addSegment(segment);
            }
        }
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!MODELLED_KEYS.contains(field.getKey())) {
                project.putExtraField(field.getKey(), objectMapper.convertValue(field.getValue(), Object.class));
            }
        }
        return project;
    }

    ObjectNode toJson(ProjectModel project) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("path", project.getAudioPath());
        double bpm = project.getBpm();
        if (bpm == Math.rint(bpm) && !Double.isInfinite(bpm)) {
            root.put("bpm", (long) bpm);
        } else {
            root.put("bpm", bpm);
        }
        ArrayNode segmentArray = root.putArray("segments");
        for (Segment segment : project.getSegments()) {
            ObjectNode segmentNode = segmentArray.addObject();
            segmentNode.put("label", segment.getLabel());
            ArrayNode barsArray = segmentNode.putArray("bars");
            for (Bar bar : segment.getBars()) {
                ArrayNode beatsArray = barsArray.addObject().putArray("beats");
                for (Beat beat : bar.getBeats()) {
                    ObjectNode beatNode = beatsArray.addObject();
                    beatNode.put("isDownbeat", beat.isDownbeat());
                    beatNode.put("start", roundToMilliseconds(beat.getStart()));
                    beatNode.put("end", roundToMilliseconds(beat.getEnd()));
                }
            }
        }
        for (Map.Entry<String, Object> field : project.getExtraFields().entrySet()) {
            root.set(field.getKey(), objectMapper.valueToTree(field.getValue()));
        }
        return root;
    }

    private JsonNode requireArray(JsonNode parent, String key, String context) throws IOException {
        JsonNode value = parent.get(key);
        if (value == null || !value.isArray()) {
            throw new IOException("Unsupported JSON format: " + context + " must contain a " + key + " array");
        }
        return value;
    }

    private double roundToMilliseconds(double seconds) {
        return Math.round(seconds * 1000.0) / 1000.0;
    }
}
