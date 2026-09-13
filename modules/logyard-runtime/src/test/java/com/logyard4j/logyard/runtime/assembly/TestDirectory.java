package com.logyard4j.logyard.runtime.assembly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Explicitly owned temporary directory for dependency-free release-critical tests. */
final class TestDirectory implements AutoCloseable {
    private final Path path;

    private TestDirectory(Path path) {
        this.path = path;
    }

    static TestDirectory create(String prefix) throws IOException {
        return new TestDirectory(Files.createTempDirectory(prefix));
    }

    Path path() {
        return path;
    }

    @Override
    public void close() throws IOException {
        try (var descendants = Files.walk(path)) {
            for (Path descendant : descendants.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(descendant);
            }
        }
    }
}
