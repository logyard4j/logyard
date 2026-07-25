package com.zsumz.logyard.output.json.file;

import com.zsumz.logyard.output.json.file.lease.FileLease;
import com.zsumz.logyard.output.json.file.rotation.ArchiveMaintenance;
import com.zsumz.logyard.output.json.file.rotation.ArchiveNaming;
import com.zsumz.logyard.output.json.file.rotation.RotationPolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Single-owner record writer that rotates only between complete JSON Lines records. */
final class RotatingFileWriter implements AutoCloseable {
    private final Path path;
    private final int bufferBytes;
    private final boolean append;
    private final RotationPolicy policy;
    private final ArchiveNaming naming;
    private FileLease lease;
    private ArchiveMaintenance maintenance;
    private BufferedFileWriter active;
    private boolean closed;

    RotatingFileWriter(
            Path path,
            int bufferBytes,
            boolean append,
            RotationPolicy policy) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.bufferBytes = bufferBytes;
        this.append = append;
        this.policy = policy;
        naming = policy == null ? null : new ArchiveNaming(this.path);
        lease = FileLease.acquire(this.path);
    }

    void initializeForDirectUse() {
        ensureOpen();
        initialize();
    }

    void writeRecord(byte[] record, byte terminator) {
        Objects.requireNonNull(record, "record");
        ensureOpen();
        initialize();
        if (maintenance != null) {
            maintenance.throwIfFailed();
        }
        long recordBytes = (long) record.length + 1L;
        if (policy != null
                && active.logicalBytes() > 0
                && wouldExceed(active.logicalBytes(), recordBytes, policy.maximumBytes())) {
            rotate();
        }
        active.write(record, terminator);
    }

    void flush() {
        ensureOpen();
        initialize();
        active.flush();
        if (maintenance != null) {
            maintenance.throwIfFailed();
        }
    }

    boolean closed() {
        return closed;
    }

    int maintenanceQueueCapacity() {
        return maintenance == null ? 0 : maintenance.queueCapacity();
    }

    int maintenanceQueuedTasks() {
        return maintenance == null ? 0 : maintenance.queuedTasks();
    }

    boolean maintenanceWorkerAlive() {
        return active == null || maintenance == null || maintenance.workerAlive();
    }

    boolean maintenanceClosing() {
        return maintenance != null && maintenance.closing();
    }

    String maintenanceFailureType() {
        return maintenance == null ? null : maintenance.failureType();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        if (active != null) {
            try {
                active.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
        }
        try {
            if (maintenance != null) {
                maintenance.close(policy.maintenanceShutdownTimeout());
            } else if (lease != null) {
                lease.close();
            }
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void rotate() {
        active.close();
        Path archive = naming.nextArchive();
        try {
            moveActiveToArchive(archive);
            active = BufferedFileWriter.open(path, bufferBytes, false);
            maintenance.submit(archive);
        } catch (RuntimeException failure) {
            tryReopenAfterRotationFailure(failure);
            throw failure;
        }
    }

    private void initialize() {
        if (active != null) {
            return;
        }
        BufferedFileWriter opened = BufferedFileWriter.open(path, bufferBytes, append);
        try {
            if (policy != null) {
                maintenance = ArchiveMaintenance.start(naming, policy, lease);
                lease = null;
            }
            active = opened;
        } catch (RuntimeException | Error failure) {
            try {
                opened.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            if (lease != null) {
                try {
                    lease.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                lease = null;
            }
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

    private void tryReopenAfterRotationFailure(RuntimeException primaryFailure) {
        try {
            if (Files.exists(path)) {
                active = BufferedFileWriter.open(path, bufferBytes, true);
            }
        } catch (RuntimeException recoveryFailure) {
            primaryFailure.addSuppressed(recoveryFailure);
        }
    }

    private static boolean wouldExceed(long current, long recordBytes, long maximum) {
        return recordBytes > maximum - current;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Logyard JSON output is closed: " + path);
        }
    }
}
