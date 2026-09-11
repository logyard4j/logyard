package com.logyard4j.runtime.bootstrap;

import com.logyard4j.runtime.reload.ConfigurationSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Immutable source identity and content reader used by the public configuration-source facade. */
record ConfigurationSourceDescriptor(
        String description,
        Path baseDirectory,
        Path watchPath,
        Object identity,
        ContentReader reader) {

    ConfigurationSourceDescriptor {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(baseDirectory, "baseDirectory");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(reader, "reader");
    }

    ConfigurationSnapshot snapshot() throws IOException {
        return ConfigurationSnapshot.capture(description, baseDirectory, watchPath, reader.read());
    }

    @FunctionalInterface
    interface ContentReader {
        byte[] read() throws IOException;
    }
}
