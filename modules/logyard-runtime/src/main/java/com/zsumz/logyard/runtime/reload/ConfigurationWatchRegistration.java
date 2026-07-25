package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.api.annotation.InternalApi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provisional ownership of a parent-directory watch registration.
 *
 * <p>Registration is deliberately separate from watcher policy so configuration can be reread after
 * the filesystem boundary is established and the final snapshot can supply debounce, diagnostics,
 * and shutdown behavior.</p>
 */
@InternalApi
public final class ConfigurationWatchRegistration implements AutoCloseable {
    private final Path source;
    private final Path filename;
    private final WatchService watchService;
    private final AtomicBoolean claimed = new AtomicBoolean();

    private ConfigurationWatchRegistration(Path source, Path filename, WatchService watchService) {
        this.source = Objects.requireNonNull(source, "source");
        this.filename = Objects.requireNonNull(filename, "filename");
        this.watchService = Objects.requireNonNull(watchService, "watchService");
    }

    public static ConfigurationWatchRegistration open(Path source) throws IOException {
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

    void claim() {
        if (!claimed.compareAndSet(false, true)) {
            throw new IllegalStateException("configuration watch registration is already claimed");
        }
    }

    Path source() {
        return source;
    }

    Path filename() {
        return filename;
    }

    WatchService watchService() {
        return watchService;
    }

    @Override
    public void close() {
        try {
            watchService.close();
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to close Logyard configuration watch registration", failure);
        }
    }
}
