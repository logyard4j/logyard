package com.zsumz.logyard.runtime.reload;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.util.Objects;

record ConfigurationWatchRegistration(Path source, Path filename, WatchService watchService) {
    ConfigurationWatchRegistration {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(watchService, "watchService");
    }

    static ConfigurationWatchRegistration open(Path source) throws IOException {
        Path normalizedSource = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        Path filename = normalizedSource.getFileName();
        if (filename == null) {
            throw new IOException("configuration path has no filename: " + source);
        }
        Path parent = normalizedSource.getParent();
        if (parent == null) {
            throw new IOException("configuration path has no parent directory: " + source);
        }

        WatchService watchService = FileSystems.getDefault().newWatchService();
        try {
            parent.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            return new ConfigurationWatchRegistration(normalizedSource, filename, watchService);
        } catch (IOException | RuntimeException registrationFailure) {
            try {
                watchService.close();
            } catch (IOException closeFailure) {
                registrationFailure.addSuppressed(closeFailure);
            }
            throw registrationFailure;
        }
    }
}
