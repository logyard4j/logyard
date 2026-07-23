package com.zsumz.logyard.output.json.stream;

import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.diagnostics.HealthStatus;
import com.zsumz.logyard.api.spi.encoding.EventEncoder;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.api.spi.diagnostics.HealthContributor;
import com.zsumz.logyard.api.event.LogEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Thread-safe JSONL sink with bounded, time-based flushing. */
public final class JsonLinesSink implements EventSink, HealthContributor {
    private final Writer writer;
    private final EventEncoder encoder;
    private final long flushIntervalNanos;
    private final boolean closeWriter;
    private long nextFlushNanos;
    private boolean closed;

    public JsonLinesSink(
            Writer writer,
            EventEncoder encoder,
            Duration flushInterval,
            boolean closeWriter) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(flushInterval, "flushInterval");
        if (flushInterval.isNegative()) {
            throw new IllegalArgumentException("flush interval must not be negative");
        }
        flushIntervalNanos = saturatedNanos(flushInterval);
        nextFlushNanos = System.nanoTime() + flushIntervalNanos;
        this.closeWriter = closeWriter;
    }

    @Override
    public synchronized void accept(LogEvent event) {
        ensureOpen();
        try {
            writer.write(encoder.encode(event));
            writer.write('\n');
            long now = System.nanoTime();
            if (flushIntervalNanos == 0 || now >= nextFlushNanos) {
                writer.flush();
                nextFlushNanos = now + flushIntervalNanos;
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to write Logyard JSON event", failure);
        }
    }

    @Override
    public synchronized void flush() {
        ensureOpen();
        try {
            writer.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to flush Logyard JSON output", failure);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
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

    @Override
    public synchronized ComponentHealth health(String componentName) {
        return new ComponentHealth(
                componentName,
                "json-stream-output",
                closed ? HealthStatus.STOPPED : HealthStatus.HEALTHY,
                Map.of("format", "jsonl", "writer", writer.getClass().getName()),
                Map.of());
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Logyard JSON stream output is closed");
        }
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
