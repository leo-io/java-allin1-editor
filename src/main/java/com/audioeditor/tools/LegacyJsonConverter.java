package com.audioeditor.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Standalone command-line converter for legacy allin1 JSON files.
 *
 * <p>For each legacy file, the original is renamed to {@code .bak}, and a new
 * nested-format JSON is written at the original path.
 */
public final class LegacyJsonConverter {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Set<String> LEGACY_TOP_LEVEL_KEYS = Set.of(
            "beats", "downbeats", "beat_positions");

    private static final Set<String> MODELLED_TOP_LEVEL_KEYS = Set.of(
            "path", "bpm", "segments", "beats", "downbeats", "beat_positions");

    private LegacyJsonConverter() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || hasHelpFlag(args)) {
            printUsage();
            System.exit(args.length == 0 ? 1 : 0);
        }

        boolean recursive = false;
        List<Path> inputs = new ArrayList<>();
        for (String arg : args) {
            if ("--recursive".equals(arg) || "-r".equals(arg)) {
                recursive = true;
            } else {
                inputs.add(Path.of(arg));
            }
        }

        if (inputs.isEmpty()) {
            System.err.println("No input files or directories provided.");
            printUsage();
            System.exit(1);
        }

        int converted = 0;
        int skipped = 0;
        int failed = 0;

        for (Path input : inputs) {
            try {
                for (Path jsonFile : expandInput(input, recursive)) {
                    ConversionResult result = convertFile(jsonFile);
                    switch (result.status()) {
                        case CONVERTED -> {
                            converted++;
                            System.out.printf("Converted %s (backup: %s, segments=%d, bars=%d, beats=%d)%n",
                                    jsonFile, result.backupPath(), result.segmentCount(), result.barCount(), result.beatCount());
                        }
                        case SKIPPED_NOT_LEGACY -> {
                            skipped++;
                            System.out.printf("Skipped %s (already new format or not legacy)%n", jsonFile);
                        }
                    }
                }
            } catch (Exception ex) {
                failed++;
                System.err.printf("Failed %s: %s%n", input, ex.getMessage());
            }
        }

        System.out.printf("Done. converted=%d skipped=%d failed=%d%n", converted, skipped, failed);
        if (failed > 0) {
            System.exit(2);
        }
    }

    public static ConversionResult convertFile(Path jsonFile) throws IOException {
        if (!Files.isRegularFile(jsonFile)) {
            throw new IOException("Not a file: " + jsonFile);
        }

        JsonNode root = JSON.readTree(jsonFile.toFile());
        if (!isLegacyRoot(root)) {
            return ConversionResult.skipped();
        }

        ObjectNode converted = convertLegacyRoot(root);
        Path backupPath = nextBackupPath(jsonFile);
        Files.move(jsonFile, backupPath, StandardCopyOption.ATOMIC_MOVE);
        JSON.writerWithDefaultPrettyPrinter().writeValue(jsonFile.toFile(), converted);

        Counts counts = count(converted);
        return ConversionResult.converted(backupPath, counts.segments(), counts.bars(), counts.beats());
    }

    static ObjectNode convertLegacyRoot(JsonNode root) throws IOException {
        JsonNode beatsNode = requireArray(root, "beats");
        JsonNode positionsNode = requireArray(root, "beat_positions");
        JsonNode segmentsNode = requireArray(root, "segments");

        if (beatsNode.size() != positionsNode.size()) {
            throw new IOException("beats count does not match beat_positions count");
        }

        List<Double> beats = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < beatsNode.size(); i++) {
            beats.add(beatsNode.get(i).asDouble());
            positions.add(positionsNode.get(i).asInt());
        }

        ObjectNode out = JSON.createObjectNode();
        if (root.has("path")) {
            out.set("path", root.get("path"));
        }
        if (root.has("bpm")) {
            out.set("bpm", root.get("bpm"));
        }

        ArrayNode outSegments = out.putArray("segments");
        for (int si = 0; si < segmentsNode.size(); si++) {
            JsonNode oldSegment = segmentsNode.get(si);
            double segmentStart = oldSegment.path("start").asDouble();
            double segmentEnd = oldSegment.path("end").asDouble();
            boolean isLastSegment = si == segmentsNode.size() - 1;

            ObjectNode newSegment = outSegments.addObject();
            newSegment.put("label", oldSegment.path("label").asText(""));
            ArrayNode bars = newSegment.putArray("bars");

            ArrayNode currentBarBeats = null;
            int segmentBeatCount = 0;
            for (int i = 0; i < beats.size(); i++) {
                double start = beats.get(i);
                boolean insideSegment = isLastSegment
                        ? start >= segmentStart && start <= segmentEnd
                        : start >= segmentStart && start < segmentEnd;
                if (!insideSegment) {
                    continue;
                }

                if (currentBarBeats == null || positions.get(i) == 1) {
                    currentBarBeats = bars.addObject().putArray("beats");
                }

                double end = nextBeatStartInSegment(beats, i, segmentEnd, segmentStart, segmentEnd, isLastSegment);
                addBeat(currentBarBeats, positions.get(i) == 1, start, end);
                segmentBeatCount++;
            }

            if (segmentBeatCount == 0) {
                ArrayNode syntheticBar = bars.addObject().putArray("beats");
                addBeat(syntheticBar, true, segmentStart, segmentEnd);
            }
        }

        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!MODELLED_TOP_LEVEL_KEYS.contains(field.getKey())) {
                out.set(field.getKey(), field.getValue());
            }
        }

        return out;
    }

    private static double nextBeatStartInSegment(List<Double> beats, int currentIndex,
                                                double fallbackEnd, double segmentStart,
                                                double segmentEnd, boolean isLastSegment) {
        for (int i = currentIndex + 1; i < beats.size(); i++) {
            double next = beats.get(i);
            boolean insideSegment = isLastSegment
                    ? next >= segmentStart && next <= segmentEnd
                    : next >= segmentStart && next < segmentEnd;
            if (insideSegment) {
                return next;
            }
            if (next >= segmentEnd) {
                break;
            }
        }
        return fallbackEnd;
    }

    private static void addBeat(ArrayNode beats, boolean isDownbeat, double start, double end) {
        ObjectNode beat = beats.addObject();
        beat.put("isDownbeat", isDownbeat);
        beat.put("start", roundToMs(start));
        beat.put("end", roundToMs(end));
    }

    private static boolean isLegacyRoot(JsonNode root) {
        for (String key : LEGACY_TOP_LEVEL_KEYS) {
            if (root.has(key)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode requireArray(JsonNode root, String key) throws IOException {
        JsonNode value = root.get(key);
        if (value == null || !value.isArray()) {
            throw new IOException("Legacy JSON must contain array: " + key);
        }
        return value;
    }

    private static List<Path> expandInput(Path input, boolean recursive) throws IOException {
        if (Files.isRegularFile(input)) {
            return List.of(input);
        }
        if (!Files.isDirectory(input)) {
            throw new IOException("Not found: " + input);
        }

        int maxDepth = recursive ? Integer.MAX_VALUE : 1;
        try (Stream<Path> paths = Files.walk(input, maxDepth)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().toLowerCase().endsWith(".bak"))
                    .toList();
        }
    }

    private static Path nextBackupPath(Path original) {
        Path backup = original.resolveSibling(original.getFileName() + ".bak");
        if (!Files.exists(backup)) {
            return backup;
        }
        for (int i = 2; ; i++) {
            Path candidate = original.resolveSibling(original.getFileName() + ".bak" + i);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    private static Counts count(ObjectNode root) {
        int segments = 0;
        int bars = 0;
        int beats = 0;
        JsonNode segmentsNode = root.get("segments");
        if (segmentsNode != null && segmentsNode.isArray()) {
            segments = segmentsNode.size();
            for (JsonNode segment : segmentsNode) {
                JsonNode barsNode = segment.get("bars");
                if (barsNode != null && barsNode.isArray()) {
                    bars += barsNode.size();
                    for (JsonNode bar : barsNode) {
                        JsonNode beatsNode = bar.get("beats");
                        if (beatsNode != null && beatsNode.isArray()) {
                            beats += beatsNode.size();
                        }
                    }
                }
            }
        }
        return new Counts(segments, bars, beats);
    }

    private static boolean hasHelpFlag(String[] args) {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg) || "/?".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  java -cp target/java-allin1-editor.jar com.audioeditor.tools.LegacyJsonConverter [--recursive] <file-or-directory>...

                Converts legacy allin1 JSON files in place:
                  song.json     -> new nested-format JSON
                  song.json.bak -> original legacy JSON
                """);
    }

    private static double roundToMs(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public enum Status {
        CONVERTED,
        SKIPPED_NOT_LEGACY
    }

    public record ConversionResult(Status status, Path backupPath, int segmentCount, int barCount, int beatCount) {
        static ConversionResult skipped() {
            return new ConversionResult(Status.SKIPPED_NOT_LEGACY, null, 0, 0, 0);
        }

        static ConversionResult converted(Path backupPath, int segmentCount, int barCount, int beatCount) {
            return new ConversionResult(Status.CONVERTED, backupPath, segmentCount, barCount, beatCount);
        }
    }

    private record Counts(int segments, int bars, int beats) {
    }
}
