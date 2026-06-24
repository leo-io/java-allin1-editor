package com.audioeditor;

import com.audioeditor.io.AllIn1JsonFileRepository;
import com.audioeditor.model.Beat;
import com.audioeditor.model.ProjectModel;
import com.audioeditor.model.Segment;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonIORoundTripTest {

    @Test
    void loadSaveLoadPreservesData() throws Exception {
        File src = new File("20260612_163540-sleeping.json");
        if (!src.exists()) {
            // sample not present in this environment; skip silently
            return;
        }

        ProjectModel a = AllIn1JsonFileRepository.INSTANCE.loadFromFile(src);
        File tmp = Files.createTempFile("editor-roundtrip", ".json").toFile();
        tmp.deleteOnExit();
        AllIn1JsonFileRepository.INSTANCE.saveToFile(a, tmp);
        ProjectModel b = AllIn1JsonFileRepository.INSTANCE.loadFromFile(tmp);

        assertEquals(a.getAudioPath(), b.getAudioPath());
        assertEquals(a.getBpm(), b.getBpm(), 1e-9);
        assertEquals(a.getBeats().size(), b.getBeats().size());
        assertEquals(a.getDownbeats().size(), b.getDownbeats().size());
        assertEquals(a.getSegments().size(), b.getSegments().size());

        for (int i = 0; i < a.getBeats().size(); i++) {
            Beat ba = a.getBeats().get(i);
            Beat bb = b.getBeats().get(i);
            assertEquals(ba.getTime(), bb.getTime(), 1e-3);
            assertEquals(ba.getPosition(), bb.getPosition());
        }
        for (int i = 0; i < a.getSegments().size(); i++) {
            Segment sa = a.getSegments().get(i);
            Segment sb = b.getSegments().get(i);
            assertEquals(sa.getStart(), sb.getStart(), 1e-3);
            assertEquals(sa.getEnd(), sb.getEnd(), 1e-3);
            assertEquals(sa.getLabel(), sb.getLabel());
        }
    }

    @Test
    void editsPersist() throws Exception {
        ProjectModel m = new ProjectModel();
        m.setAudioPath("C:/audio/song.wav");
        m.setBpm(123.5);
        m.getBeats().add(new Beat(0.5, 1));
        m.getBeats().add(new Beat(1.0, 2));
        m.getDownbeats().add(0.5);
        m.getSegments().add(new Segment(0.0, 10.0, "intro"));

        File tmp = Files.createTempFile("editor-edits", ".json").toFile();
        tmp.deleteOnExit();
        AllIn1JsonFileRepository.INSTANCE.saveToFile(m, tmp);
        ProjectModel r = AllIn1JsonFileRepository.INSTANCE.loadFromFile(tmp);

        assertEquals("C:/audio/song.wav", r.getAudioPath());
        assertEquals(123.5, r.getBpm(), 1e-9);
        assertEquals(2, r.getBeats().size());
        assertEquals(2, r.getBeats().get(1).getPosition());
        assertEquals(1, r.getDownbeats().size());
        assertEquals("intro", r.getSegments().get(0).getLabel());
        assertTrue(tmp.length() > 0);
    }
}
