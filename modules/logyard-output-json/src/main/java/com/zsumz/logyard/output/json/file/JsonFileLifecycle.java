package com.zsumz.logyard.output.json.file;

import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.output.json.file.rotation.RotationPolicy;
import com.zsumz.logyard.output.json.flush.FlushScheduler;
import com.zsumz.logyard.output.json.flush.TimedFlushController;

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
    private volatile boolean active;
    private volatile boolean closed;

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
        this.active = active;
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
        active = true;
    }

    void requireActive() {
        ensureOpen();
        ensureActive();
    }

    void writeRecord(byte[] json) {
        requireActive();
        synchronized (writerState) {
            requireActive();
            try {
                writer.writeRecord(json, (byte) '\n');
                timedFlush.recordWritten();
            } catch (RuntimeException | Error failure) {
                if (writer.terminallyFailed()) {
                    timedFlush.cancelPending();
                }
                throw failure;
            }
        }
    }

    void flush() {
        synchronized (writerState) {
            ensureOpen();
            if (!active) {
                return;
            }
            try {
                writer.flushIfInitialized();
            } finally {
                timedFlush.flushed();
            }
        }
    }

    ComponentHealth health(String componentName) {
        WriterHealthSnapshot snapshot;
        synchronized (writerState) {
            snapshot = writer.healthSnapshot();
        }
        return JsonFileHealth.component(componentName, path, rotationPolicy != null, closed, snapshot);
    }

    @Override
    public void close() {
        synchronized (writerState) {
            if (closed) {
                return;
            }
            closed = true;
        }
        timedFlush.close();
        synchronized (writerState) {
            writer.close();
        }
    }

    private void flushOnDeadline() {
        synchronized (writerState) {
            if (!timedFlush.flushIsCurrent() || closed || !active) {
                return;
            }
            try {
                writer.flushIfInitialized();
            } finally {
                timedFlush.flushCompleted();
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Logyard JSON output is closed: " + path);
        }
    }

    private void ensureActive() {
        if (!active) {
            throw new IllegalStateException("Logyard JSON output is not active: " + path);
        }
    }
}
