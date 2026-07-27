package com.zsumz.logyard.output.json.stream;

import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.spi.encoding.EventEncoder;
import com.zsumz.logyard.api.spi.encoding.EventEncoderBoundary;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.api.spi.diagnostics.HealthContributor;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.failure.FailureIsolation;
import com.zsumz.logyard.output.json.flush.FlushScheduler;
import com.zsumz.logyard.output.json.flush.TimedFlushController;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Thread-safe JSONL sink with bounded, time-based flushing.
 *
 * <p>Encoding happens outside the writer-state monitor. Concurrent records are written atomically
 * with unspecified relative order, which keeps extension callbacks free to invoke other sink methods.</p>
 *
 * <p>A caller-supplied {@link Writer} must not recursively invoke lifecycle methods such as
 * {@link #flush()} or {@link #close()} on this same sink. Reentrant Writer lifecycle callbacks are unsupported.</p>
 */
public final class JsonLinesSink implements EventSink, HealthContributor {
    private final Object writerState = new Object();
    private final Writer writer;
    private final EventEncoder encoder;
    private final boolean closeWriter;
    private final JsonStreamState state = new JsonStreamState();
    private final TimedFlushController timedFlush;

    public JsonLinesSink(
            Writer writer,
            EventEncoder encoder,
            Duration flushInterval,
            boolean closeWriter) {
        this(writer, encoder, flushInterval, closeWriter, FlushScheduler.shared());
    }

    JsonLinesSink(
            Writer writer,
            EventEncoder encoder,
            Duration flushInterval,
            boolean closeWriter,
            FlushScheduler scheduler) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.encoder = EventEncoderBoundary.guard(encoder);
        this.closeWriter = closeWriter;
        timedFlush = new TimedFlushController(flushInterval, Objects.requireNonNull(scheduler, "scheduler"), this::flushOnDeadline);
    }

    @Override
    public void accept(LogEvent event) {
        requireOpen();
        String encoded = encoder.encode(Objects.requireNonNull(event, "event"));
        synchronized (writerState) {
            requireOpen();
            try {
                writer.write(encoded);
                writer.write('\n');
                timedFlush.recordWritten();
            } catch (Throwable failure) {
                throw fail("failed to write Logyard JSON event", failure);
            }
        }
    }

    @Override
    public void flush() {
        synchronized (writerState) {
            requireOpen();
            try {
                writer.flush();
            } catch (Throwable failure) {
                throw fail("failed to flush Logyard JSON output", failure);
            } finally {
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
            Throwable primaryFailure = state.failure();
            if (primaryFailure != null) {
                closeAfterFailure(primaryFailure);
                return;
            }
            try {
                if (closeWriter) {
                    writer.close();
                } else {
                    writer.flush();
                }
            } catch (Throwable failure) {
                throw fail("failed to close Logyard JSON output", failure);
            }
            state.completeClose();
        }
    }

    @Override
    public ComponentHealth health(String componentName) {
        synchronized (writerState) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("format", "jsonl");
            details.put("writer", writer.getClass().getName());
            if (state.failureType() != null) {
                details.put("writer_failure", state.failureType());
            }
            return new ComponentHealth(
                    componentName,
                    "json-stream-output",
                    state.healthStatus(),
                    details,
                    Map.of());
        }
    }

    private void flushOnDeadline() {
        synchronized (writerState) {
            if (!timedFlush.flushIsCurrent() || state.failed()) {
                return;
            }
            try {
                writer.flush();
            } catch (Throwable failure) {
                throw fail("failed to flush Logyard JSON output on schedule", failure);
            } finally {
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
            writer.close();
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

    private void requireOpen() {
        synchronized (writerState) {
            state.requireOpen();
        }
    }
}
