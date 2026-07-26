package com.zsumz.logyard.output.json.stream;

import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.spi.encoding.EventEncoder;
import com.zsumz.logyard.api.spi.encoding.EventEncoderBoundary;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.api.spi.diagnostics.HealthContributor;
import com.zsumz.logyard.api.event.LogEvent;
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
 * in encoding-completion order, which keeps extension callbacks free to invoke other sink methods.</p>
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
            } catch (IOException failure) {
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
            } catch (IOException failure) {
                throw fail("failed to flush Logyard JSON output", failure);
            } finally {
                timedFlush.flushed();
            }
        }
    }

    @Override
    public void close() {
        synchronized (writerState) {
            if (state.closed()) {
                return;
            }
            timedFlush.close();
            IOException primaryFailure = state.failure();
            state.close();
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
            } catch (IOException failure) {
                throw new UncheckedIOException("failed to close Logyard JSON output", failure);
            }
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
            if (state.closed() || state.failed()) {
                return;
            }
            try {
                writer.flush();
            } catch (IOException failure) {
                throw fail("failed to flush Logyard JSON output on schedule", failure);
            } finally {
                timedFlush.flushed();
            }
        }
    }

    private UncheckedIOException fail(String message, IOException failure) {
        state.failed(failure);
        timedFlush.cancelPending();
        return new UncheckedIOException(message, failure);
    }

    private void closeAfterFailure(IOException primaryFailure) {
        if (!closeWriter) {
            return;
        }
        try {
            writer.close();
        } catch (IOException cleanupFailure) {
            if (cleanupFailure != primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
            throw new UncheckedIOException("failed to close failed Logyard JSON output", primaryFailure);
        }
    }

    private void requireOpen() {
        synchronized (writerState) {
            state.requireOpen();
        }
    }
}
