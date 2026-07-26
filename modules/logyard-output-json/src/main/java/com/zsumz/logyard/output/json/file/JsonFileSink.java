package com.zsumz.logyard.output.json.file;

import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.encoding.EventEncoder;
import com.zsumz.logyard.api.spi.encoding.EventEncoderBoundary;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.api.spi.diagnostics.HealthContributor;
import com.zsumz.logyard.output.json.encoding.JsonEncoder;
import com.zsumz.logyard.output.json.encoding.ResourceAttributes;
import com.zsumz.logyard.output.json.file.rotation.RotationPolicy;
import com.zsumz.logyard.output.json.flush.FlushScheduler;
import com.zsumz.logyard.output.json.flush.TimedFlushController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Exclusive UTF-8 JSONL file output with optional record-boundary rotation.
 *
 * <p>Encoding happens outside the writer-state monitor. Concurrent records are written atomically
 * in encoding-completion order, which keeps extension callbacks free to invoke other sink methods.</p>
 *
 * <p>A prepared sink reserves its path without opening the data file. Activation, an unused flush,
 * and an unused close remain nondestructive; only the first accepted record opens the data file.</p>
 */
public final class JsonFileSink implements EventSink, HealthContributor {
    public static final int MIN_BUFFER_BYTES = 1_024;
    public static final int MAX_BUFFER_BYTES = 16 * 1_024 * 1_024;

    private final Object writerState = new Object();
    private final Path path;
    private final EventEncoder encoder;
    private final RotatingFileWriter writer;
    private final RotationPolicy rotationPolicy;
    private final TimedFlushController timedFlush;
    private volatile boolean active;
    private volatile boolean closed;

    public JsonFileSink(
            Path path,
            ResourceAttributes resource,
            int bufferBytes,
            Duration flushInterval,
            boolean append) {
        this(path, resource, bufferBytes, flushInterval, append, null);
    }

    public JsonFileSink(
            Path path,
            ResourceAttributes resource,
            int bufferBytes,
            Duration flushInterval,
            boolean append,
            RotationPolicy rotationPolicy) {
        this(path, new JsonEncoder(Objects.requireNonNull(resource, "resource")), bufferBytes, flushInterval, append, rotationPolicy);
    }

    public JsonFileSink(
            Path path,
            EventEncoder encoder,
            int bufferBytes,
            Duration flushInterval,
            boolean append,
            RotationPolicy rotationPolicy) {
        this(
                path,
                encoder,
                bufferBytes,
                flushInterval,
                append,
                rotationPolicy,
                true,
                FlushScheduler.shared(),
                BufferedFileWriter::open);
    }

    JsonFileSink(
            Path path,
            EventEncoder encoder,
            int bufferBytes,
            Duration flushInterval,
            boolean append,
            RotationPolicy rotationPolicy,
            boolean active,
            FlushScheduler scheduler,
            DataFileOpener dataFiles) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.encoder = EventEncoderBoundary.guard(encoder);
        if (bufferBytes < MIN_BUFFER_BYTES || bufferBytes > MAX_BUFFER_BYTES) {
            throw new IllegalArgumentException(
                    "JSON buffer must be between " + MIN_BUFFER_BYTES + " and " + MAX_BUFFER_BYTES + " bytes");
        }
        this.rotationPolicy = rotationPolicy;
        timedFlush = new TimedFlushController(flushInterval, Objects.requireNonNull(scheduler, "scheduler"), this::flushOnDeadline);
        writer = new RotatingFileWriter(
                this.path, bufferBytes, append, rotationPolicy, Objects.requireNonNull(dataFiles, "dataFiles"));
        this.active = active;
        if (active) {
            writer.initializeForDirectUse();
        }
    }

    /**
     * Reserves and validates a JSON output without opening or truncating its durable data file.
     *
     * @param path durable JSON Lines path
     * @param encoder event encoder
     * @param bufferBytes bounded writer buffer
     * @param flushInterval maximum interval between flushes
     * @param append whether committed delivery appends instead of replacing existing contents
     * @param rotationPolicy optional rotation policy
     * @return prepared exclusive sink
     */
    public static JsonFileSink prepare(
            Path path,
            EventEncoder encoder,
            int bufferBytes,
            Duration flushInterval,
            boolean append,
            RotationPolicy rotationPolicy) {
        return new JsonFileSink(
                path,
                encoder,
                bufferBytes,
                flushInterval,
                append,
                rotationPolicy,
                false,
                FlushScheduler.shared(),
                BufferedFileWriter::open);
    }

    /**
     * Makes a successfully assembled output eligible for delivery.
     *
     * <p>Activation and flushing an unused sink perform no file I/O. The first accepted event opens the durable file.</p>
     */
    public void activate() {
        if (closed) {
            throw new IllegalStateException("cannot activate closed Logyard JSON output: " + path);
        }
        active = true;
    }

    public Path path() {
        return path;
    }

    public RotationPolicy rotationPolicy() {
        return rotationPolicy;
    }

    @Override
    public void accept(LogEvent event) {
        ensureOpen();
        ensureActive();
        byte[] json = encoder.encode(Objects.requireNonNull(event, "event")).getBytes(StandardCharsets.UTF_8);
        synchronized (writerState) {
            ensureOpen();
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

    @Override
    public void flush() {
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

    @Override
    public ComponentHealth health(String componentName) {
        WriterHealthSnapshot snapshot;
        synchronized (writerState) {
            snapshot = writer.healthSnapshot();
        }
        return JsonFileHealth.component(componentName, path, rotationPolicy != null, closed, snapshot);
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
}
