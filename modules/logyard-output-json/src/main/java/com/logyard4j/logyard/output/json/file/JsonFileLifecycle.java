package com.logyard4j.logyard.output.json.file;

import com.logyard4j.logyard.api.diagnostics.ComponentHealth;
import com.logyard4j.logyard.output.json.file.rotation.RotationPolicy;
import com.logyard4j.logyard.output.json.flush.FlushScheduler;
import com.logyard4j.logyard.output.json.flush.TimedFlushController;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Serializes durable JSON-file state while leaving event encoding outside the output monitor. */
final class JsonFileLifecycle implements AutoCloseable {
    private final Object writerState = new Object();
    private final Path path;
    private final RotatingFileWriter writer;
    private final RotationPolicy rotationPolicy;
    private final TimedFlushController timedFlush;
    private volatile FilePhase phase;
    private volatile String ioOperation = "idle";

    JsonFileLifecycle(
            Path path,
            int bufferBytes,
            Duration flushInterval,
            boolean append,
            RotationPolicy rotationPolicy,
            boolean active,
            FlushScheduler scheduler,
            DataFileOpener dataFiles) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (bufferBytes < JsonFileSink.MIN_BUFFER_BYTES || bufferBytes > JsonFileSink.MAX_BUFFER_BYTES) {
            throw new IllegalArgumentException(
                    "JSON buffer must be between " + JsonFileSink.MIN_BUFFER_BYTES + " and " + JsonFileSink.MAX_BUFFER_BYTES + " bytes");
        }
        this.rotationPolicy = rotationPolicy;
        timedFlush = new TimedFlushController(flushInterval, Objects.requireNonNull(scheduler, "scheduler"), this::flushOnDeadline);
        writer = new RotatingFileWriter(this.path, bufferBytes, append, rotationPolicy, Objects.requireNonNull(dataFiles, "dataFiles"));
        phase = active ? FilePhase.ACTIVE : FilePhase.PREPARED;
        if (active) {
            writer.initializeForDirectUse();
        }
    }

    Path path() {
        return path;
    }

    RotationPolicy rotationPolicy() {
        return rotationPolicy;
    }

    void activate() {
        ensureOpen();
        phase = FilePhase.ACTIVE;
    }

    void requireActive() {
        ensureOpen();
        ensureActive();
    }

    void writeRecord(byte[] json, int length) {
        requireActive();
        synchronized (writerState) {
            requireActive();
            ioOperation = "write";
            try {
                writer.writeRecord(json, length, (byte) '\n');
                timedFlush.recordWritten();
            } catch (RuntimeException | Error failure) {
                if (writer.terminallyFailed()) {
                    timedFlush.cancelPending();
                }
                throw failure;
            } finally {
                ioOperation = "idle";
            }
        }
    }

    void flush() {
        synchronized (writerState) {
            ensureOpen();
            if (phase == FilePhase.PREPARED) {
                return;
            }
            ioOperation = "flush";
            try {
                writer.flushIfInitialized();
            } finally {
                ioOperation = "idle";
                timedFlush.flushed();
            }
        }
    }

    ComponentHealth health(String componentName) {
        FilePhase currentPhase = phase;
        return JsonFileHealth.component(componentName, path, rotationPolicy != null,
                currentPhase == FilePhase.CLOSED, currentPhase == FilePhase.CLOSING, ioOperation, writer.healthSnapshot());
    }

    @Override
    public void close() {
        synchronized (writerState) {
            if (phase == FilePhase.CLOSED || phase == FilePhase.CLOSING) {
                return;
            }
            phase = FilePhase.CLOSING;
        }
        timedFlush.close();
        synchronized (writerState) {
            ioOperation = "close";
            try {
                writer.close();
            } finally {
                phase = FilePhase.CLOSED;
                ioOperation = "idle";
            }
        }
    }

    private void flushOnDeadline() {
        synchronized (writerState) {
            if (!timedFlush.flushIsCurrent() || phase != FilePhase.ACTIVE) {
                return;
            }
            ioOperation = "scheduled_flush";
            try {
                writer.flushIfInitialized();
            } finally {
                ioOperation = "idle";
                timedFlush.flushCompleted();
            }
        }
    }

    private void ensureOpen() {
        if (phase == FilePhase.CLOSED || phase == FilePhase.CLOSING) {
            throw new IllegalStateException("Logyard JSON output is closed: " + path);
        }
    }

    private void ensureActive() {
        if (phase == FilePhase.PREPARED) {
            throw new IllegalStateException("Logyard JSON output is not active: " + path);
        }
    }

    private enum FilePhase {
        PREPARED,
        ACTIVE,
        CLOSING,
        CLOSED
    }
}
