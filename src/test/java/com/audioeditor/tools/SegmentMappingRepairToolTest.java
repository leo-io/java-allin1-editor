package com.audioeditor.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentMappingRepairToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void rebuildsBarsFirstThenMapsSegmentsToEvenBarRanges() throws Exception {
        JsonNode root = JSON.readTree("""
                {
                  "path": "C:/audio/song.wav",
                  "bpm": 122,
                  "segments": [
                    {"label": "verse", "bars": [
                      {"beats": [
                        {"isDownbeat": true, "start": 0.0, "end": 1.0},
                        {"isDownbeat": false, "start": 1.0, "end": 2.0},
                        {"isDownbeat": false, "start": 2.0, "end": 3.0},
                        {"isDownbeat": false, "start": 3.0, "end": 4.0}
                      ]},
                      {"beats": [
                        {"isDownbeat": true, "start": 4.0, "end": 5.0},
                        {"isDownbeat": false, "start": 5.0, "end": 6.0},
                        {"isDownbeat": false, "start": 6.0, "end": 7.0},
                        {"isDownbeat": false, "start": 7.0, "end": 8.0}
                      ]},
                      {"beats": [
                        {"isDownbeat": true, "start": 8.0, "end": 9.0}
                      ]}
                    ]},
                    {"label": "chorus", "bars": [
                      {"beats": [
                        {"isDownbeat": false, "start": 9.0, "end": 10.0},
                        {"isDownbeat": false, "start": 10.0, "end": 11.0},
                        {"isDownbeat": false, "start": 11.0, "end": 12.0}
                      ]},
                      {"beats": [
                        {"isDownbeat": true, "start": 12.0, "end": 13.0},
                        {"isDownbeat": false, "start": 13.0, "end": 14.0},
                        {"isDownbeat": false, "start": 14.0, "end": 15.0},
                        {"isDownbeat": false, "start": 15.0, "end": 16.0}
                      ]}
                    ]}
                  ]
                }
                """);

        SegmentMappingRepairTool.RepairResult result = SegmentMappingRepairTool.repairRoot(root);
        JsonNode repairedSegments = result.repairedRoot().get("segments");

        assertEquals(2, repairedSegments.size());
        assertEquals("verse", repairedSegments.get(0).get("label").asText());
        assertEquals("chorus", repairedSegments.get(1).get("label").asText());
        assertEquals(2, repairedSegments.get(0).get("bars").size());
        assertEquals(2, repairedSegments.get(1).get("bars").size());

        JsonNode chorusFirstBar = repairedSegments.at("/1/bars/0/beats");
        assertEquals(8.0, chorusFirstBar.get(0).get("start").asDouble(), 1e-9);
        assertTrue(chorusFirstBar.get(0).get("isDownbeat").asBoolean());
        assertFalse(chorusFirstBar.get(1).get("isDownbeat").asBoolean());
        assertFalse(chorusFirstBar.get(2).get("isDownbeat").asBoolean());
        assertFalse(chorusFirstBar.get(3).get("isDownbeat").asBoolean());
    }

    @Test
    void choosesBeatPhaseFromDownbeatEvidenceAndDropsStrayEdges() throws Exception {
        JsonNode root = JSON.readTree("""
                {
                  "path": "C:/audio/song.wav",
                  "bpm": 122,
                  "segments": [
                    {"label": "intro", "bars": [
                      {"beats": [
                        {"isDownbeat": false, "start": 0.0, "end": 1.0},
                        {"isDownbeat": true, "start": 1.0, "end": 2.0},
                        {"isDownbeat": false, "start": 2.0, "end": 3.0},
                        {"isDownbeat": false, "start": 3.0, "end": 4.0},
                        {"isDownbeat": false, "start": 4.0, "end": 5.0},
                        {"isDownbeat": true, "start": 5.0, "end": 6.0},
                        {"isDownbeat": false, "start": 6.0, "end": 7.0},
                        {"isDownbeat": false, "start": 7.0, "end": 8.0},
                        {"isDownbeat": false, "start": 8.0, "end": 9.0}
                      ]}
                    ]}
                  ]
                }
                """);

        SegmentMappingRepairTool.RepairResult result = SegmentMappingRepairTool.repairRoot(root);

        assertEquals(1, result.report().phaseOffset());
        assertEquals(1, result.report().droppedLeadingBeats());
        assertEquals(0, result.report().droppedTrailingBeats());
        assertEquals(8, result.report().beatCountAfter());
        assertEquals(1.0, result.repairedRoot().at("/segments/0/bars/0/beats/0/start").asDouble(), 1e-9);
    }

    @Test
    void repairFileWritesBackupAndRepairedJson() throws Exception {
        Path dir = Files.createTempDirectory("mapping-repair-tool");
        Path file = dir.resolve("song.json");
        Files.writeString(file, """
                {
                  "path": "C:/audio/song.wav",
                  "bpm": 122,
                  "segments": [
                    {"label": "intro", "bars": [
                      {"beats": [
                        {"isDownbeat": true, "start": 0.0, "end": 1.0},
                        {"isDownbeat": false, "start": 1.0, "end": 2.0},
                        {"isDownbeat": false, "start": 2.0, "end": 3.0},
                        {"isDownbeat": false, "start": 3.0, "end": 4.0},
                        {"isDownbeat": true, "start": 4.0, "end": 5.0},
                        {"isDownbeat": false, "start": 5.0, "end": 6.0},
                        {"isDownbeat": false, "start": 6.0, "end": 7.0},
                        {"isDownbeat": false, "start": 7.0, "end": 8.0}
                      ]}
                    ]}
                  ]
                }
                """);

        SegmentMappingRepairTool.FileRepairResult result = SegmentMappingRepairTool.repairFile(file, false);
        JsonNode repaired = JSON.readTree(file.toFile());

        assertTrue(Files.exists(result.backupPath()));
        assertEquals(1, repaired.get("segments").size());
        assertEquals(2, repaired.at("/segments/0/bars").size());
        assertEquals(4, repaired.at("/segments/0/bars/0/beats").size());
        assertEquals(4, repaired.at("/segments/0/bars/1/beats").size());
    }
}
