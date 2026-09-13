package com.logyard4j.logyard.output.json.stream;

import com.logyard4j.logyard.api.diagnostics.ComponentHealth;
import com.logyard4j.logyard.api.spi.diagnostics.HealthContributor;
import com.logyard4j.logyard.api.failure.FailureIsolation;
import com.logyard4j.logyard.output.json.flush.FlushScheduler;
import com.logyard4j.logyard.output.json.flush.TimedFlushController;
import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Serializes record delivery and transport lifecycle while publishing independent health snapshots. */
final class JsonStreamLifecycle<T> implements Closeable, Flushable, HealthContributor {
    private final Object writerState = new Object();
    private final Closeable resource;
    private final Flushable writer;
    private final RecordWriter<T> records;
    private final boolean closeWriter;
    private final JsonStreamState state = new JsonStreamState();
    private final TimedFlushController timedFlush;
    private volatile String ioOperation = "idle";

    <W extends Closeable & Flushable> JsonStreamLifecycle(
            W writer,
            Duration flushInterval,
            boolean closeWriter,
            FlushScheduler scheduler,
            RecordWriter<T> records) {
        resource = Objects.requireNonNull(writer, "writer");
        this.writer = writer;
        this.records = Objects.requireNonNull(records, "records");
        this.closeWriter = closeWriter;
        timedFlush = new TimedFlushController(flushInterval, Objects.requireNonNull(scheduler, "scheduler"), this::flushOnDeadline);
    }

    void write(T record, int length) {
        synchronized (writerState) {
            requireOpen();
            ioOperation = "write";
            try {
                records.write(record, length);
                timedFlush.recordWritten();
            } catch (Throwable failure) {
                throw fail("failed to write Logyard JSON event", failure);
            } finally {
                ioOperation = "idle";
            }
        }
    }

    @Override
    public void flush() {
        synchronized (writerState) {
            requireOpen();
            ioOperation = "flush";
            try {
                writer.flush();
            } catch (Throwable failure) {
                throw fail("failed to flush Logyard JSON output", failure);
            } finally {
                ioOperation = "idle";
                timedFlush.flushed();
            }
        }
    }

    @Override
    public void close() {
        synchronized (writerState) {
            if (!state.startClose()) {
                return;
            }
        }
        timedFlush.close();
        synchronized (writerState) {
            ioOperation = "close";
            try {
                Throwable primaryFailure = state.failure();
                if (primaryFailure != null) {
                    closeAfterFailure(primaryFailure);
                    return;
                }
                if (closeWriter) {
                    resource.close();
                } else {
                    writer.flush();
                }
            } catch (Throwable failure) {
                throw fail("failed to close Logyard JSON output", failure);
            } finally {
                ioOperation = "idle";
            }
            state.completeClose();
        }
    }

    @Override
    public ComponentHealth health(String componentName) {
        JsonStreamState.Snapshot snapshot = state.snapshot();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("format", "jsonl");
        details.put("writer", writer.getClass().getName());
        details.put("io_operation", ioOperation);
        if (snapshot.failureType() != null) {
            details.put("writer_failure", snapshot.failureType());
        }
        return new ComponentHealth(componentName, "json-stream-output", snapshot.status(), details, Map.of());
    }

    private void flushOnDeadline() {
        synchronized (writerState) {
            if (!timedFlush.flushIsCurrent() || state.failed()) {
                return;
            }
            ioOperation = "scheduled_flush";
            try {
                writer.flush();
            } catch (Throwable failure) {
                throw fail("failed to flush Logyard JSON output on schedule", failure);
            } finally {
                ioOperation = "idle";
                timedFlush.flushCompleted();
            }
        }
    }

    private RuntimeException fail(String message, Throwable failure) {
        state.failed(failure);
        timedFlush.cancelPending();
        FailureIsolation.prepareForRecovery(failure);
        return unchecked(message, failure);
    }

    private void closeAfterFailure(Throwable primaryFailure) {
        if (!closeWriter) {
            state.completeClose();
            return;
        }
        try {
            resource.close();
            state.completeClose();
        } catch (Throwable cleanupFailure) {
            FailureIsolation.prepareForRecovery(cleanupFailure);
            if (cleanupFailure != primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
            throw unchecked("failed to close failed Logyard JSON output", primaryFailure);
        }
    }

    private static RuntimeException unchecked(String message, Throwable failure) {
        if (failure instanceof IOException checked) {
            return new UncheckedIOException(message, checked);
        }
        if (failure instanceof RuntimeException unchecked) {
            return unchecked;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(message, failure);
    }

    void requireOpen() {
        synchronized (writerState) {
            state.requireOpen();
        }
    }

    @FunctionalInterface
    interface RecordWriter<T> {
        void write(T record, int length) throws IOException;
    }
}
