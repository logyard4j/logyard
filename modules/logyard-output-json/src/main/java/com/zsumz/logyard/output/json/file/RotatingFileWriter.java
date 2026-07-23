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
    private final RotationPolicy policy;
    private final ArchiveNaming naming;
    private final ArchiveMaintenance maintenance;
    private BufferedFileWriter active;
    private boolean closed;

    RotatingFileWriter(
            Path path,
            int bufferBytes,
            boolean append,
            RotationPolicy policy) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.bufferBytes = bufferBytes;
        this.policy = policy;
        FileLease lease = FileLease.acquire(this.path);
        BufferedFileWriter opened = null;
        ArchiveMaintenance started = null;
        try {
            opened = BufferedFileWriter.open(this.path, bufferBytes, append);
            if (policy != null) {
                naming = new ArchiveNaming(this.path);
                started = ArchiveMaintenance.start(naming, policy, lease);
            } else {
                naming = null;
            }
            active = opened;
            maintenance = started;
            if (maintenance == null) {
                directLease = lease;
            } else {
                directLease = null;
            }
        } catch (RuntimeException | Error failure) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (started == null) {
                try {
                    lease.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    private final FileLease directLease;

    void writeRecord(byte[] record) {
        Objects.requireNonNull(record, "record");
        ensureOpen();
        if (maintenance != null) {
            maintenance.throwIfFailed();
        }
        if (policy != null
                && active.logicalBytes() > 0
                && wouldExceed(active.logicalBytes(), record.length, policy.maximumBytes())) {
            rotate();
        }
        active.write(record);
    }

    void flush() {
        ensureOpen();
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
        return maintenance == null || maintenance.workerAlive();
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
        try {
            active.close();
        } catch (RuntimeException closeFailure) {
            failure = closeFailure;
        }
        try {
            if (maintenance != null) {
                maintenance.close(policy.maintenanceShutdownTimeout());
            } else if (directLease != null) {
                directLease.close();
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

    private static boolean wouldExceed(long current, int recordBytes, long maximum) {
        return recordBytes > maximum - current;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Logyard JSON output is closed: " + path);
        }
    }
}
