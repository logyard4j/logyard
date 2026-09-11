package com.logyard4j.output.json.file;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Performs nondestructive structural validation before a file output can be initialized. */
final class FileOutputPathValidator {
    private FileOutputPathValidator() {
    }

    static void validateParent(Path output) {
        Path path = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
        if (path.getFileName() == null) {
            throw new IllegalArgumentException("Logyard JSON output must name a file: " + path);
        }
        Path parent = path.getParent();
        if (parent != null
                && Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Logyard JSON output parent must be a directory: " + parent);
        }
    }

    static void validateOutput(Path output) {
        Path path = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Logyard JSON output must be a regular file when it exists: " + path);
        }
    }
}
