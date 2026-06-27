package com.audioeditor.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Standalone repair tool for nested allin1 JSON segment/bar/beat mappings.
 *
 * <p>The repair order is deliberate:
 * <ol>
 *     <li>Flatten and sort the global beat stream.</li>
 *     <li>Choose the best 4/4 phase and rebuild bars as 1 downbeat + 3 beats.</li>
 *     <li>Map original segment labels onto contiguous, even-length bar ranges.</li>
 * </ol>
 *
 * <p>Beat start times are preserved. Beat end times are normalized to the next
 * beat start so rebuilt bars are contiguous.
 */
public final class SegmentMappingRepairTool {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final int BEATS_PER_BAR = 4;
    private static final double TIME_EPSILON_SECONDS = 1e-6;
    private static final double INFINITE_COST = 1e18;

    private SegmentMappingRepairTool() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || hasHelpFlag(args)) {
            printUsage();
            System.exit(args.length == 0 ? 1 : 0);
        }

        boolean recursive = false;
        boolean dryRun = false;
        List<Path> inputs = new ArrayList<>();
        for (String arg : args) {
            switch (arg) {
                case "--recursive", "-r" -> recursive = true;
                case "--dry-run", "-n" -> dryRun = true;
                default -> inputs.add(Path.of(arg));
            }
        }

        if (inputs.isEmpty()) {
            System.err.println("No input files or directories provided.");
            printUsage();
            System.exit(1);
        }

        int repaired = 0;
        int failed = 0;
        for (Path input : inputs) {
            try {
                for (Path jsonFile : expandInput(input, recursive)) {
                    FileRepairResult result = repairFile(jsonFile, dryRun);
                    repaired++;
                    RepairReport report = result.repairResult().report();
                    String action = dryRun ? "Would repair" : "Repaired";
                    System.out.printf(
                            "%s %s (backup=%s, segments=%d->%d, bars=%d, beats=%d->%d, phase=%d, droppedBeats=%d)%n",
                            action,
                            jsonFile,
                            result.backupPath(),
                            report.segmentCountBefore(),
                            report.segmentCountAfter(),
                            report.barCountAfter(),
                            report.beatCountBefore(),
                            report.beatCountAfter(),
                            report.phaseOffset(),
                            report.droppedLeadingBeats() + report.droppedTrailingBeats() + report.deduplicatedBeats());
                }
            } catch (Exception ex) {
                failed++;
                System.err.printf("Failed %s: %s%n", input, ex.getMessage());
            }
        }

        System.out.printf("Done. repaired=%d failed=%d%n", repaired, failed);
        if (failed > 0) {
            System.exit(2);
        }
    }

    public static FileRepairResult repairFile(Path jsonFile, boolean dryRun) throws IOException {
        if (!Files.isRegularFile(jsonFile)) {
            throw new IOException("Not a file: " + jsonFile);
        }

        JsonNode root = JSON.readTree(jsonFile.toFile());
        RepairResult repairResult = repairRoot(root);
        Path backupPath = null;
        if (!dryRun) {
            backupPath = nextBackupPath(jsonFile);
            Files.copy(jsonFile, backupPath);
            Path tempFile = jsonFile.resolveSibling(jsonFile.getFileName() + ".repair.tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), repairResult.repairedRoot());
            replaceFile(tempFile, jsonFile);
        }
        return new FileRepairResult(jsonFile, backupPath, dryRun, repairResult);
    }

    public static RepairResult repairRoot(JsonNode root) throws IOException {
        if (root == null || !root.isObject()) {
            throw new IOException("Expected a JSON object root.");
        }
        if (root.has("beats") || root.has("downbeats") || root.has("beat_positions")) {
            throw new IOException("Legacy JSON detected. Convert it before running segment mapping repair.");
        }

        JsonNode segmentsNode = requireArray(root, "segments");
        List<SegmentHint> segmentHints = readSegmentHints(segmentsNode);
        List<BeatEntry> originalBeats = readBeatEntries(segmentsNode);
        if (originalBeats.size() < BEATS_PER_BAR * 2) {
            throw new IOException("Need at least 8 beats to build an even-bar segment mapping.");
        }

        List<BeatEntry> dedupedBeats = sortAndDeduplicate(originalBeats);
        double medianBeatSeconds = medianBeatIntervalSeconds(dedupedBeats);
        List<BeatEntry> normalizedBeats = normalizeBeatEnds(dedupedBeats, medianBeatSeconds);
        PhasePlan phasePlan = chooseBestPhase(normalizedBeats);
        List<BarEntry> bars = rebuildBars(normalizedBeats, phasePlan);
        List<OutputSegment> outputSegments = mapSegmentsToBars(segmentHints, bars, medianBeatSeconds);

        ObjectNode repairedRoot = JSON.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!"segments".equals(field.getKey())) {
                repairedRoot.set(field.getKey(), field.getValue());
            }
        }
        writeSegments(repairedRoot.putArray("segments"), outputSegments);
        validateRepairedSegments(outputSegments);

        RepairReport report = new RepairReport(
                segmentHints.size(),
                outputSegments.size(),
                originalBeats.size(),
                bars.size() * BEATS_PER_BAR,
                bars.size(),
                phasePlan.offset(),
                phasePlan.droppedLeadingBeats(),
                phasePlan.droppedTrailingBeats(),
                originalBeats.size() - dedupedBeats.size(),
                segmentHints.size() - outputSegments.size(),
                phasePlan.score());
        return new RepairResult(repairedRoot, report);
    }

    private static List<SegmentHint> readSegmentHints(JsonNode segmentsNode) throws IOException {
        List<SegmentHint> hints = new ArrayList<>();
        for (int si = 0; si < segmentsNode.size(); si++) {
            JsonNode segmentNode = segmentsNode.get(si);
            String label = segmentNode.path("label").asText("");
            JsonNode barsNode = requireArray(segmentNode, "bars");
            double start = Double.POSITIVE_INFINITY;
            double end = Double.NEGATIVE_INFINITY;
            int beatCount = 0;
            for (int bi = 0; bi < barsNode.size(); bi++) {
                JsonNode beatsNode = requireArray(barsNode.get(bi), "beats");
                for (int i = 0; i < beatsNode.size(); i++) {
                    JsonNode beatNode = beatsNode.get(i);
                    double beatStart = readFiniteDouble(beatNode, "start", "segment " + si + ", bar " + bi);
                    double beatEnd = beatNode.hasNonNull("end")
                            ? readFiniteDouble(beatNode, "end", "segment " + si + ", bar " + bi)
                            : beatStart;
                    start = Math.min(start, beatStart);
                    end = Math.max(end, beatEnd);
                    beatCount++;
                }
            }
            if (beatCount == 0) {
                start = 0;
                end = 0;
            }
            hints.add(new SegmentHint(si, label, start, end, beatCount));
        }
        return hints;
    }

    private static List<BeatEntry> readBeatEntries(JsonNode segmentsNode) throws IOException {
        List<BeatEntry> beats = new ArrayList<>();
        int order = 0;
        for (int si = 0; si < segmentsNode.size(); si++) {
            JsonNode barsNode = requireArray(segmentsNode.get(si), "bars");
            for (int bi = 0; bi < barsNode.size(); bi++) {
                JsonNode beatsNode = requireArray(barsNode.get(bi), "beats");
                for (int i = 0; i < beatsNode.size(); i++) {
                    JsonNode beatNode = beatsNode.get(i);
                    double start = readFiniteDouble(beatNode, "start", "segment " + si + ", bar " + bi);
                    double end = beatNode.hasNonNull("end")
                            ? readFiniteDouble(beatNode, "end", "segment " + si + ", bar " + bi)
                            : start;
                    boolean downbeat = beatNode.path("isDownbeat").asBoolean(false);
                    beats.add(new BeatEntry(start, end, downbeat, order++));
                }
            }
        }
        return beats;
    }

    private static List<BeatEntry> sortAndDeduplicate(List<BeatEntry> beats) {
        List<BeatEntry> sorted = new ArrayList<>(beats);
        sorted.sort(Comparator
                .comparingDouble(BeatEntry::start)
                .thenComparingInt(BeatEntry::originalOrder));

        List<BeatEntry> deduped = new ArrayList<>();
        for (BeatEntry beat : sorted) {
            if (!deduped.isEmpty()) {
                BeatEntry previous = deduped.get(deduped.size() - 1);
                if (Math.abs(previous.start() - beat.start()) <= TIME_EPSILON_SECONDS) {
                    deduped.set(deduped.size() - 1, previous.mergeDuplicate(beat));
                    continue;
                }
            }
            deduped.add(beat);
        }
        return deduped;
    }

    private static List<BeatEntry> normalizeBeatEnds(List<BeatEntry> beats, double medianBeatSeconds) {
        List<BeatEntry> normalized = new ArrayList<>(beats.size());
        for (int i = 0; i < beats.size(); i++) {
            BeatEntry beat = beats.get(i);
            double end = i < beats.size() - 1 ? beats.get(i + 1).start() : beat.end();
            if (!Double.isFinite(end) || end <= beat.start() + TIME_EPSILON_SECONDS) {
                end = beat.start() + medianBeatSeconds;
            }
            normalized.add(beat.withEnd(end));
        }
        return normalized;
    }

    private static PhasePlan chooseBestPhase(List<BeatEntry> beats) throws IOException {
        PhasePlan best = null;
        int maxOffset = Math.min(BEATS_PER_BAR, beats.size());
        for (int offset = 0; offset < maxOffset; offset++) {
            int availableBeats = beats.size() - offset;
            int completeBars = availableBeats / BEATS_PER_BAR;
            if (completeBars % 2 != 0) {
                completeBars--;
            }
            if (completeBars <= 0) {
                continue;
            }

            int includedBeats = completeBars * BEATS_PER_BAR;
            int droppedTrailing = beats.size() - offset - includedBeats;
            double alignment = downbeatAlignmentScore(beats, offset, includedBeats);
            double score = (offset + droppedTrailing) * 1000.0 - alignment;
            PhasePlan candidate = new PhasePlan(offset, includedBeats, offset, droppedTrailing, score);
            if (best == null || candidate.score() < best.score()) {
                best = candidate;
            }
        }

        if (best == null) {
            throw new IOException("Could not find an even number of complete 4-beat bars.");
        }
        return best;
    }

    private static double downbeatAlignmentScore(List<BeatEntry> beats, int offset, int includedBeats) {
        double score = 0;
        for (int i = 0; i < includedBeats; i++) {
            BeatEntry beat = beats.get(offset + i);
            int position = i % BEATS_PER_BAR;
            if (position == 0) {
                score += beat.originalDownbeat() ? 5 : -3;
            } else {
                score += beat.originalDownbeat() ? -2 : 1;
            }
        }
        return score;
    }

    private static List<BarEntry> rebuildBars(List<BeatEntry> beats, PhasePlan phasePlan) {
        List<BarEntry> bars = new ArrayList<>();
        int endExclusive = phasePlan.offset() + phasePlan.includedBeatCount();
        for (int i = phasePlan.offset(); i < endExclusive; i += BEATS_PER_BAR) {
            List<BeatEntry> barBeats = new ArrayList<>(BEATS_PER_BAR);
            for (int p = 0; p < BEATS_PER_BAR; p++) {
                barBeats.add(beats.get(i + p));
            }
            bars.add(new BarEntry(barBeats));
        }
        return bars;
    }

    private static List<OutputSegment> mapSegmentsToBars(List<SegmentHint> hints,
                                                         List<BarEntry> bars,
                                                         double medianBeatSeconds) throws IOException {
        int segmentCount = hints.size();
        int barCount = bars.size();
        double[][] cost = new double[segmentCount + 1][barCount + 1];
        int[][] previousBarIndex = new int[segmentCount + 1][barCount + 1];
        for (int i = 0; i <= segmentCount; i++) {
            for (int j = 0; j <= barCount; j++) {
                cost[i][j] = INFINITE_COST;
                previousBarIndex[i][j] = -1;
            }
        }
        cost[0][0] = 0;

        for (int si = 0; si < segmentCount; si++) {
            SegmentHint hint = hints.get(si);
            for (int from = 0; from <= barCount; from++) {
                if (cost[si][from] >= INFINITE_COST) {
                    continue;
                }

                relax(cost, previousBarIndex, si + 1, from,
                        cost[si][from] + zeroSegmentCost(hint), from);

                for (int to = from + 2; to <= barCount; to += 2) {
                    double segmentCost = segmentAssignmentCost(hint, bars, from, to, medianBeatSeconds);
                    relax(cost, previousBarIndex, si + 1, to, cost[si][from] + segmentCost, from);
                }
            }
        }

        if (cost[segmentCount][barCount] >= INFINITE_COST) {
            throw new IOException("Could not assign all rebuilt bars to even-length segments.");
        }

        List<OutputSegment> outputSegments = new ArrayList<>();
        int to = barCount;
        for (int si = segmentCount; si > 0; si--) {
            int from = previousBarIndex[si][to];
            if (from < 0) {
                throw new IOException("Internal segment mapping reconstruction failed.");
            }
            if (from < to) {
                SegmentHint hint = hints.get(si - 1);
                outputSegments.add(new OutputSegment(hint.label(), new ArrayList<>(bars.subList(from, to))));
            }
            to = from;
        }
        if (to != 0) {
            throw new IOException("Internal segment mapping did not consume all bars.");
        }
        Collections.reverse(outputSegments);
        if (outputSegments.isEmpty()) {
            throw new IOException("Repair would produce no segments.");
        }
        return outputSegments;
    }

    private static void relax(double[][] cost, int[][] previousBarIndex,
                              int segmentIndex, int barIndex, double newCost, int previous) {
        if (newCost < cost[segmentIndex][barIndex]) {
            cost[segmentIndex][barIndex] = newCost;
            previousBarIndex[segmentIndex][barIndex] = previous;
        }
    }

    private static double segmentAssignmentCost(SegmentHint hint, List<BarEntry> bars,
                                                int from, int to, double medianBeatSeconds) {
        double actualStart = bars.get(from).start();
        double actualEnd = bars.get(to - 1).end();
        int actualBeats = (to - from) * BEATS_PER_BAR;
        double timeCost = (Math.abs(actualStart - hint.start()) + Math.abs(actualEnd - hint.end()))
                / Math.max(medianBeatSeconds, TIME_EPSILON_SECONDS);
        double sizeCost = Math.abs(actualBeats - hint.beatCount()) * 0.5;
        return timeCost + sizeCost;
    }

    private static double zeroSegmentCost(SegmentHint hint) {
        return 2.0 + hint.beatCount() * 0.5;
    }

    private static void writeSegments(ArrayNode segmentsNode, List<OutputSegment> outputSegments) {
        for (OutputSegment segment : outputSegments) {
            ObjectNode segmentNode = segmentsNode.addObject();
            segmentNode.put("label", segment.label());
            ArrayNode barsNode = segmentNode.putArray("bars");
            for (BarEntry bar : segment.bars()) {
                ObjectNode barNode = barsNode.addObject();
                ArrayNode beatsNode = barNode.putArray("beats");
                for (int i = 0; i < bar.beats().size(); i++) {
                    BeatEntry beat = bar.beats().get(i);
                    ObjectNode beatNode = beatsNode.addObject();
                    beatNode.put("isDownbeat", i == 0);
                    beatNode.put("start", roundToMs(beat.start()));
                    beatNode.put("end", roundToMs(beat.end()));
                }
            }
        }
    }

    private static void validateRepairedSegments(List<OutputSegment> outputSegments) throws IOException {
        for (OutputSegment segment : outputSegments) {
            int bars = segment.bars().size();
            if (bars == 0 || bars % 2 != 0) {
                throw new IOException("Repair produced a segment with an invalid bar count: " + segment.label());
            }
            for (BarEntry bar : segment.bars()) {
                if (bar.beats().size() != BEATS_PER_BAR) {
                    throw new IOException("Repair produced a bar with " + bar.beats().size() + " beats.");
                }
            }
        }
    }

    private static double medianBeatIntervalSeconds(List<BeatEntry> beats) {
        List<Double> intervals = new ArrayList<>();
        for (int i = 0; i < beats.size() - 1; i++) {
            double interval = beats.get(i + 1).start() - beats.get(i).start();
            if (interval > TIME_EPSILON_SECONDS && Double.isFinite(interval)) {
                intervals.add(interval);
            }
        }
        if (intervals.isEmpty()) {
            return 0.5;
        }
        intervals.sort(Double::compareTo);
        return intervals.get(intervals.size() / 2);
    }

    private static JsonNode requireArray(JsonNode node, String key) throws IOException {
        JsonNode value = node.get(key);
        if (value == null || !value.isArray()) {
            throw new IOException("Expected array field: " + key);
        }
        return value;
    }

    private static double readFiniteDouble(JsonNode node, String key, String context) throws IOException {
        if (!node.hasNonNull(key)) {
            throw new IOException("Missing " + key + " in " + context);
        }
        double value = node.get(key).asDouble(Double.NaN);
        if (!Double.isFinite(value)) {
            throw new IOException("Invalid " + key + " in " + context);
        }
        return value;
    }

    private static List<Path> expandInput(Path input, boolean recursive) throws IOException {
        return FileToolSupport.expandJsonInput(input, recursive, name -> !name.contains(".bak"));
    }

    private static Path nextBackupPath(Path original) {
        return FileToolSupport.nextBackupPath(original, ".mapping-repair.bak");
    }

    private static void replaceFile(Path source, Path target) throws IOException {
        FileToolSupport.replaceAtomicallyWhenSupported(source, target);
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
                  java -cp target/java-allin1-editor.jar com.audioeditor.tools.SegmentMappingRepairTool [options] <file-or-directory>...

                Options:
                  -n, --dry-run      Analyze and print the planned repair without writing files.
                  -r, --recursive    Walk directories recursively.

                Repairs nested-format JSON in place:
                  song.json                         -> repaired JSON
                  song.json.mapping-repair.bak      -> original JSON backup
                """);
    }

    private static double roundToMs(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public record FileRepairResult(Path file, Path backupPath, boolean dryRun, RepairResult repairResult) {
    }

    public record RepairResult(ObjectNode repairedRoot, RepairReport report) {
    }

    public record RepairReport(int segmentCountBefore,
                               int segmentCountAfter,
                               int beatCountBefore,
                               int beatCountAfter,
                               int barCountAfter,
                               int phaseOffset,
                               int droppedLeadingBeats,
                               int droppedTrailingBeats,
                               int deduplicatedBeats,
                               int droppedSegments,
                               double score) {
    }

    private record BeatEntry(double start, double end, boolean originalDownbeat, int originalOrder) {
        BeatEntry withEnd(double newEnd) {
            return new BeatEntry(start, newEnd, originalDownbeat, originalOrder);
        }

        BeatEntry mergeDuplicate(BeatEntry other) {
            return new BeatEntry(
                    start,
                    Math.max(end, other.end),
                    originalDownbeat || other.originalDownbeat,
                    Math.min(originalOrder, other.originalOrder));
        }
    }

    private record SegmentHint(int originalIndex, String label, double start, double end, int beatCount) {
    }

    private record BarEntry(List<BeatEntry> beats) {
        double start() {
            return beats.get(0).start();
        }

        double end() {
            return beats.get(beats.size() - 1).end();
        }
    }

    private record OutputSegment(String label, List<BarEntry> bars) {
    }

    private record PhasePlan(int offset,
                             int includedBeatCount,
                             int droppedLeadingBeats,
                             int droppedTrailingBeats,
                             double score) {
    }
}
