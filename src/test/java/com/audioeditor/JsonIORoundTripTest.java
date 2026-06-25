package com.audioeditor;

import com.audioeditor.io.AllIn1JsonFileRepository;
import com.audioeditor.model.Bar;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonIORoundTripTest {

    @Test
    void loadSaveLoadPreservesData() throws Exception {
        ProjectModel sourceModel = sampleNestedProject();
        File src = Files.createTempFile("editor-source", ".json").toFile();
        src.deleteOnExit();
        AllIn1JsonFileRepository.INSTANCE.saveToFile(sourceModel, src);

        ProjectModel a = AllIn1JsonFileRepository.INSTANCE.loadFromFile(src);
        File tmp = Files.createTempFile("editor-roundtrip", ".json").toFile();
        tmp.deleteOnExit();
        AllIn1JsonFileRepository.INSTANCE.saveToFile(a, tmp);
        ProjectModel b = AllIn1JsonFileRepository.INSTANCE.loadFromFile(tmp);

        assertEquals(a.getAudioPath(), b.getAudioPath());
        assertEquals(a.getBpm(), b.getBpm(), 1e-9);
        assertEquals(a.getSegments().size(), b.getSegments().size());

        for (int i = 0; i < a.getSegments().size(); i++) {
            Segment sa = a.getSegments().get(i);
            Segment sb = b.getSegments().get(i);
            assertEquals(sa.getStart(), sb.getStart(), 1e-3);
            assertEquals(sa.getEnd(), sb.getEnd(), 1e-3);
            assertEquals(sa.getLabel(), sb.getLabel());
            assertEquals(sa.getBars().size(), sb.getBars().size());

            for (int j = 0; j < sa.getBars().size(); j++) {
                Bar ba = sa.getBars().get(j);
                Bar bb = sb.getBars().get(j);
                assertEquals(ba.getBeats().size(), bb.getBeats().size());

                for (int k = 0; k < ba.getBeats().size(); k++) {
                    Beat bea = ba.getBeats().get(k);
                    Beat beb = bb.getBeats().get(k);
                    assertEquals(bea.isDownbeat(), beb.isDownbeat());
                    assertEquals(bea.getStart(), beb.getStart(), 1e-3);
                    assertEquals(bea.getEnd(), beb.getEnd(), 1e-3);
                }
            }
        }
    }

    @Test
    void editsPersist() throws Exception {
        ProjectModel m = sampleNestedProject();

        File tmp = Files.createTempFile("editor-edits", ".json").toFile();
        tmp.deleteOnExit();
        AllIn1JsonFileRepository.INSTANCE.saveToFile(m, tmp);
        ProjectModel r = AllIn1JsonFileRepository.INSTANCE.loadFromFile(tmp);

        assertEquals("C:/audio/song.wav", r.getAudioPath());
        assertEquals(123.5, r.getBpm(), 1e-9);
        assertEquals(1, r.getSegments().size());
        assertEquals("intro", r.getSegments().get(0).getLabel());
        assertEquals(1, r.getSegments().get(0).getBars().size());
        assertEquals(2, r.getSegments().get(0).getBars().get(0).getBeats().size());
        assertTrue(r.getSegments().get(0).getBars().get(0).getBeats().get(0).isDownbeat());
        assertEquals(0.5, r.getSegments().get(0).getBars().get(0).getBeats().get(0).getStart(), 1e-9);
        assertTrue(tmp.length() > 0);
    }

    @Test
    void legacyFlatJsonIsRejected() throws Exception {
        File tmp = Files.createTempFile("editor-legacy", ".json").toFile();
        tmp.deleteOnExit();
        Files.writeString(tmp.toPath(), """
                {
                  "path": "C:/audio/song.wav",
                  "bpm": 120,
                  "beats": [0.0, 0.5],
                  "downbeats": [0.0],
                  "beat_positions": [1, 2],
                  "segments": [{"start": 0.0, "end": 1.0, "label": "intro"}]
                }
                """);

        assertThrows(Exception.class, () -> AllIn1JsonFileRepository.INSTANCE.loadFromFile(tmp));
    }

    private ProjectModel sampleNestedProject() {
        ProjectModel m = new ProjectModel();
        m.setAudioPath("C:/audio/song.wav");
        m.setBpm(123.5);

        Segment seg = new Segment("intro");
        Bar bar = new Bar();
        bar.addBeat(new Beat(true, 0.5, 1.0));
        bar.addBeat(new Beat(false, 1.0, 1.5));
        seg.addBar(bar);
        m.addSegment(seg);
        return m;
    }
}
