package com.logyard4j.runtime.installation;

import com.logyard4j.runtime.reload.ConfigurationSnapshot;
import com.logyard4j.runtime.reload.ConfigurationSnapshotReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Internal source handoff stripped of public bootstrap implementation details. */
public final class ConfigurationInstallationRequest {
    private final String description;
    private final Path watchPath;
    private final Object sourceIdentity;
    private final ConfigurationSnapshotReader snapshotReader;

    public ConfigurationInstallationRequest(
            String description,
            Path watchPath,
            Object sourceIdentity,
            ConfigurationSnapshotReader snapshotReader) {
        this.description = Objects.requireNonNull(description, "description");
        this.watchPath = watchPath == null ? null : watchPath.toAbsolutePath().normalize();
        this.sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        this.snapshotReader = Objects.requireNonNull(snapshotReader, "snapshotReader");
    }

    String description() {
        return description;
    }

    Path watchPath() {
        return watchPath;
    }

    boolean reloadable() {
        return watchPath != null;
    }

    boolean identifiesSameSource(ConfigurationInstallationRequest other) {
        return other != null && sourceIdentity.equals(other.sourceIdentity);
    }

    ConfigurationSnapshot snapshot() throws IOException {
        return snapshotReader.read();
    }

    ConfigurationSnapshotReader snapshotReader() {
        return snapshotReader;
    }
}
