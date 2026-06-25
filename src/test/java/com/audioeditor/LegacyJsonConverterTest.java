package com.audioeditor;

import com.audioeditor.tools.LegacyJsonConverter;
import com.audioeditor.tools.LegacyJsonConverter.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyJsonConverterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void convertsLegacyFileInPlaceAndCreatesBackup() throws Exception {
        Path dir = Files.createTempDirectory("legacy-json-converter");
        Path file = dir.resolve("song.json");
        Files.writeString(file, """
                {
                  "path": "C:/audio/song.wav",
                  "bpm": 125,
                  "beats": [0.0, 0.5, 1.0, 1.5, 2.0],
                  "downbeats": [0.0, 2.0],
                  "beat_positions": [1, 2, 3, 4, 1],
                  "segments": [
                    {"start": 0.0, "end": 2.0, "label": "intro"},
                    {"start": 2.0, "end": 3.0, "label": "end"}
                  ],
                  "extra": "kept"
                }
                """);

        LegacyJsonConverter.ConversionResult result = LegacyJsonConverter.convertFile(file);

        assertEquals(Status.CONVERTED, result.status());
        assertTrue(Files.exists(file));
        assertTrue(Files.exists(file.resolveSibling("song.json.bak")));

        JsonNode converted = JSON.readTree(file.toFile());
        assertFalse(converted.has("beats"));
        assertFalse(converted.has("downbeats"));
        assertFalse(converted.has("beat_positions"));
        assertEquals("kept", converted.get("extra").asText());
        assertEquals(2, converted.get("segments").size());
        assertEquals(2, result.segmentCount());
        assertEquals(2, result.barCount());
        assertEquals(5, result.beatCount());
        assertEquals(0.0, converted.at("/segments/0/bars/0/beats/0/start").asDouble(), 1e-9);
        assertEquals(2.0, converted.at("/segments/1/bars/0/beats/0/start").asDouble(), 1e-9);
        assertEquals(3.0, converted.at("/segments/1/bars/0/beats/0/end").asDouble(), 1e-9);
    }

    @Test
    void skipsAlreadyNestedFile() throws Exception {
        Path dir = Files.createTempDirectory("legacy-json-converter-new");
        Path file = dir.resolve("song.json");
        Files.writeString(file, """
                {
                  "path": "C:/audio/song.wav",
                  "bpm": 125,
                  "segments": [
                    {"label": "intro", "bars": [{"beats": [
                      {"isDownbeat": true, "start": 0.0, "end": 0.5}
                    ]}]}
                  ]
                }
                """);

        LegacyJsonConverter.ConversionResult result = LegacyJsonConverter.convertFile(file);

        assertEquals(Status.SKIPPED_NOT_LEGACY, result.status());
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(file.resolveSibling("song.json.bak")));
    }
}
