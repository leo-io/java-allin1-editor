package com.audioeditor.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ArchitectureBoundaryTest {

    @Test
    void domainAndApplicationDoNotDependOnAdapters() throws IOException {
        assertNoImports(Path.of("src/main/java/com/audioeditor/model"), List.of(
                "com.fasterxml.jackson", "javax.swing", "java.awt", "javax.sound"));
        assertNoImports(Path.of("src/main/java/com/audioeditor/application"), List.of(
                "com.fasterxml.jackson", "javax.swing", "java.awt", "javax.sound",
                "com.audioeditor.io", "com.audioeditor.audio", "com.audioeditor.ui"));
    }

    @Test
    void swingViewsDependOnPortsRatherThanConcreteAdapters() throws IOException {
        assertNoImports(Path.of("src/main/java/com/audioeditor/ui"), List.of(
                "com.audioeditor.audio.PcmWavPlaybackEngine",
                "com.audioeditor.io.AllIn1JsonFileRepository"));
    }

    private void assertNoImports(Path root, List<String> forbidden) throws IOException {
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (String dependency : forbidden) {
                    assertFalse(source.contains("import " + dependency),
                            () -> file + " imports forbidden dependency " + dependency);
                }
            }
        }
    }
}
