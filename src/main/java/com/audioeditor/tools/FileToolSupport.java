package com.audioeditor.tools;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Shared safe filesystem operations for command-line maintenance tools. */
final class FileToolSupport {

    private FileToolSupport() {
    }

    static List<Path> expandJsonInput(Path input, boolean recursive,
                                      Predicate<String> acceptedFileName) throws IOException {
        if (Files.isRegularFile(input)) {
            return List.of(input);
        }
        if (!Files.isDirectory(input)) {
            throw new IOException("Not found: " + input);
        }
        int maxDepth = recursive ? Integer.MAX_VALUE : 1;
        try (Stream<Path> paths = Files.walk(input, maxDepth)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .filter(path -> acceptedFileName.test(path.getFileName().toString().toLowerCase()))
                    .toList();
        }
    }

    static Path nextBackupPath(Path original, String suffix) {
        Path backup = original.resolveSibling(original.getFileName() + suffix);
        if (!Files.exists(backup)) {
            return backup;
        }
        for (int index = 2; ; index++) {
            Path candidate = original.resolveSibling(original.getFileName() + suffix + index);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    static void replaceAtomicallyWhenSupported(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
