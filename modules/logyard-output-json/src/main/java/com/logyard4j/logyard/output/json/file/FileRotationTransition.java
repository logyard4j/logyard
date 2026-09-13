package com.logyard4j.logyard.output.json.file;

import com.logyard4j.logyard.output.json.file.rotation.ArchiveMaintenance;
import com.logyard4j.logyard.output.json.file.rotation.ArchiveNaming;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Moves one complete active file to its archive and restores a writable active-file session. */
final class FileRotationTransition {
    private final Path path;
    private final int bufferBytes;
    private final ArchiveNaming naming;
    private final DataFileOpener dataFiles;
    private final WriterLifecycle lifecycle;

    FileRotationTransition(
            Path path,
            int bufferBytes,
            ArchiveNaming naming,
            DataFileOpener dataFiles,
            WriterLifecycle lifecycle) {
        this.path = Objects.requireNonNull(path, "path");
        this.bufferBytes = bufferBytes;
        this.naming = Objects.requireNonNull(naming, "naming");
        this.dataFiles = Objects.requireNonNull(dataFiles, "dataFiles");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    void rotate(ActiveFileSession active, ArchiveMaintenance maintenance) {
        ActiveDataFile rotating = active.detach();
        try {
            rotating.close();
        } catch (RuntimeException failure) {
            lifecycle.failed(failure);
            throw failure;
        }
        try {
            Path archive = naming.nextArchive();
            moveActiveToArchive(archive);
            Objects.requireNonNull(maintenance, "archive maintenance").submit(archive);
            active.attach(dataFiles.open(path, bufferBytes, false));
            lifecycle.operationSucceeded();
        } catch (RuntimeException failure) {
            lifecycle.recoverableOperationFailed(failure);
            reopenAfterFailure(active, failure);
            throw failure;
        }
    }

    private void moveActiveToArchive(Path archive) {
        try {
            try {
                Files.move(path, archive, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(path, archive);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to rotate Logyard JSON output " + path, failure);
        }
    }

    private void reopenAfterFailure(ActiveFileSession active, RuntimeException primaryFailure) {
        try {
            if (Files.exists(path)) {
                active.attach(dataFiles.open(path, bufferBytes, true));
            }
        } catch (RuntimeException recoveryFailure) {
            primaryFailure.addSuppressed(recoveryFailure);
        }
    }
}
