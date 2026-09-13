package com.logyard4j.logyard.output.json.file;

import com.logyard4j.logyard.api.failure.FailureIsolation;
import com.logyard4j.logyard.output.json.file.lease.FileLease;
import com.logyard4j.logyard.output.json.file.rotation.ArchiveMaintenance;
import com.logyard4j.logyard.output.json.file.rotation.ArchiveNaming;
import com.logyard4j.logyard.output.json.file.rotation.RotationPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Single-owner record writer that rotates only between complete JSON Lines records. */
final class RotatingFileWriter implements AutoCloseable {
    private final Path path;
    private final int bufferBytes;
    private final boolean append;
    private final RotationPolicy policy;
    private final ArchiveNaming naming;
    private final DataFileOpener dataFiles;
    private final WriterLifecycle lifecycle = new WriterLifecycle();
    private final ActiveFileSession active = new ActiveFileSession(lifecycle);
    private final ActiveFileAge age;
    private final FileRotationTransition rotation;
    private FileLease lease;
    private volatile ArchiveMaintenance maintenance;

    RotatingFileWriter(Path path, int bufferBytes, boolean append, RotationPolicy policy) {
        this(path, bufferBytes, append, policy, BufferedFileWriter::open);
    }

    RotatingFileWriter(Path path, int bufferBytes, boolean append, RotationPolicy policy, DataFileOpener dataFiles) {
        this(path, bufferBytes, append, policy, dataFiles, System::nanoTime);
    }

    RotatingFileWriter(Path path, int bufferBytes, boolean append, RotationPolicy policy,
            DataFileOpener dataFiles, LongSupplier clock) {
        age = new ActiveFileAge(Objects.requireNonNull(clock, "clock"));
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.bufferBytes = bufferBytes;
        this.append = append;
        this.policy = policy;
        this.dataFiles = Objects.requireNonNull(dataFiles, "dataFiles");
        naming = policy == null ? null : new ArchiveNaming(this.path);
        rotation = policy == null ? null : new FileRotationTransition(this.path, bufferBytes, naming, dataFiles, lifecycle);
        FileOutputPathValidator.validateParent(this.path);
        FileLease acquired = FileLease.acquire(this.path);
        try {
            FileOutputPathValidator.validateOutput(this.path);
            lease = acquired;
        } catch (RuntimeException | Error failure) {
            FileWriterInitialization.closeAfterFailure(failure, acquired::close);
            FailureIsolation.prepareForRecovery(failure);
            throw failure;
        }
    }

    void initializeForDirectUse() {
        lifecycle.requireUsable(path);
        initialize();
    }

    void writeRecord(byte[] record, byte terminator) {
        writeRecord(record, record.length, terminator);
    }

    void writeRecord(byte[] record, int length, byte terminator) {
        Objects.checkFromIndexSize(0, length, record.length);
        ensureOpen();
        initialize();
        if (maintenance != null) {
            maintenance.throwIfFailed();
        }
        long recordBytes = (long) length + 1L;
        if (policy != null && active.logicalBytes() > 0 && rotationDue(recordBytes)) {
            rotation.rotate(active, maintenance);
            age.opened(0L);
        }
        active.write(record, length, terminator);
    }

    void flush() {
        ensureOpen();
        initialize();
        active.flush();
        if (maintenance != null) {
            maintenance.throwIfFailed();
        }
    }

    void flushIfInitialized() {
        ensureOpen();
        if (!active.present()) {
            return;
        }
        active.flush();
        if (maintenance != null) {
            maintenance.throwIfFailed();
        }
    }

    boolean initialized() {
        return active.present();
    }

    boolean terminallyFailed() {
        return lifecycle.state() == WriterLifecycle.State.FAILED;
    }

    WriterHealthSnapshot healthSnapshot() {
        ArchiveMaintenance currentMaintenance = maintenance;
        WriterLifecycle.Snapshot snapshot = lifecycle.snapshot();
        return new WriterHealthSnapshot(
                snapshot.state().name(),
                snapshot.failureType(),
                currentMaintenance == null || currentMaintenance.workerAlive(),
                currentMaintenance != null && currentMaintenance.closing(),
                currentMaintenance == null ? null : currentMaintenance.failureType(),
                currentMaintenance == null ? 0 : currentMaintenance.queueCapacity(),
                currentMaintenance == null ? 0 : currentMaintenance.queuedTasks());
    }

    @Override
    public void close() {
        if (lifecycle.state() == WriterLifecycle.State.CLOSED) {
            return;
        }
        lifecycle.closed();
        RuntimeException failure = null;
        if (active.present()) {
            try {
                active.detach().close();
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
            lifecycle.closeFailed(failure);
            throw failure;
        }
    }

    private void initialize() {
        if (active.present()) {
            return;
        }
        lifecycle.requireUsable(path);
        if (lifecycle.state() == WriterLifecycle.State.OPEN) {
            boolean resuming = Files.exists(path);
            try {
                active.attach(dataFiles.open(path, bufferBytes, resuming));
                age.opened(ActiveFileAge.inheritedNanos(path, resuming));
                return;
            } catch (RuntimeException | Error failure) {
                lifecycle.recoverableOperationFailed(failure);
                throw failure;
            }
        }
        try {
            FileWriterInitialization initialization = new FileWriterInitialization(
                    path, bufferBytes, append, policy, naming, lease, dataFiles);
            initialization.initialize();
            active.attach(initialization.active());
            age.opened(ActiveFileAge.inheritedNanos(path, append));
            maintenance = initialization.maintenance();
            if (maintenance != null) {
                lease = null;
            }
            lifecycle.opened();
        } catch (RuntimeException | Error failure) {
            lease = null;
            lifecycle.failed(failure);
            throw failure;
        }
    }

    /** Applies the size and age limits at one record boundary; whichever is reached first rotates. */
    private boolean rotationDue(long recordBytes) {
        return wouldExceed(active.logicalBytes(), recordBytes, policy.maximumBytes())
                || age.exceeds(policy.maximumAge());
    }

    private static boolean wouldExceed(long current, long recordBytes, long maximum) {
        return recordBytes > maximum - current;
    }

    private void ensureOpen() {
        lifecycle.requireUsable(path);
    }
}
