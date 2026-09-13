package com.logyard4j.logyard.output.json.file;

import com.logyard4j.logyard.api.diagnostics.ComponentHealth;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.diagnostics.HealthContributor;
import com.logyard4j.logyard.api.spi.encoding.EventEncoder;
import com.logyard4j.logyard.api.spi.encoding.EventEncoderBoundary;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.output.json.encoding.JsonEncoder;
import com.logyard4j.logyard.output.json.encoding.ResourceAttributes;
import com.logyard4j.logyard.output.json.file.rotation.RotationPolicy;
import com.logyard4j.logyard.output.json.flush.FlushScheduler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/** Exclusive UTF-8 JSONL output that encodes events separately from durable file lifecycle work. */
public final class JsonFileSink implements EventSink, HealthContributor {
    public static final int MIN_BUFFER_BYTES = 1_024;
    public static final int MAX_BUFFER_BYTES = 16 * 1_024 * 1_024;

    private final Consumer<LogEvent> writeEvent;
    private final JsonFileLifecycle lifecycle;

    public JsonFileSink(Path path, ResourceAttributes resource, int bufferBytes, Duration flushInterval, boolean append) {
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
        this(path, encoder, bufferBytes, flushInterval, append, rotationPolicy, false);
    }

    /**
     * Creates an active output whose flushes optionally force the data file to storage.
     *
     * <p>When {@code fsync} is set, every flush this output completes — record-driven, timed, the one
     * that closes a file for rotation, and the one taken at shutdown — forces the channel before the
     * flush is considered complete.</p>
     */
    public JsonFileSink(
            Path path,
            EventEncoder encoder,
            int bufferBytes,
            Duration flushInterval,
            boolean append,
            RotationPolicy rotationPolicy,
            boolean fsync) {
        this(path, encoder, bufferBytes, flushInterval, append, rotationPolicy, true, FlushScheduler.shared(), dataFiles(fsync));
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
        EventEncoder guarded = EventEncoderBoundary.guard(encoder);
        writeEvent = encoder instanceof JsonEncoder json
                ? json.utf8Records(this::writeRecord)
                : event -> {
                    byte[] record = guarded.encode(event).getBytes(StandardCharsets.UTF_8);
                    writeRecord(record, record.length);
                };
        lifecycle = new JsonFileLifecycle(
                path, bufferBytes, flushInterval, append, rotationPolicy, active, scheduler, dataFiles);
    }

    /** Reserves and validates a JSON output without opening or truncating its durable data file. */
    public static JsonFileSink prepare(
            Path path,
            EventEncoder encoder,
            int bufferBytes,
            Duration flushInterval,
            boolean append,
            RotationPolicy rotationPolicy) {
        return prepare(path, encoder, bufferBytes, flushInterval, append, rotationPolicy, false);
    }

    /** Reserves a JSON output, optionally forcing its data file at the end of every flush. */
    public static JsonFileSink prepare(
            Path path,
            EventEncoder encoder,
            int bufferBytes,
            Duration flushInterval,
            boolean append,
            RotationPolicy rotationPolicy,
            boolean fsync) {
        return new JsonFileSink(
                path, encoder, bufferBytes, flushInterval, append, rotationPolicy, false, FlushScheduler.shared(), dataFiles(fsync));
    }

    private static DataFileOpener dataFiles(boolean fsync) {
        return (path, bufferBytes, append) -> BufferedFileWriter.open(path, bufferBytes, append, fsync);
    }

    /** Makes a successfully assembled output eligible for delivery. */
    public void activate() {
        lifecycle.activate();
    }

    public Path path() {
        return lifecycle.path();
    }

    public RotationPolicy rotationPolicy() {
        return lifecycle.rotationPolicy();
    }

    @Override
    public void accept(LogEvent event) {
        lifecycle.requireActive();
        writeEvent.accept(Objects.requireNonNull(event, "event"));
    }

    private void writeRecord(byte[] record, int length) {
        lifecycle.writeRecord(record, length);
    }

    @Override
    public void flush() {
        lifecycle.flush();
    }

    @Override
    public void close() {
        lifecycle.close();
    }

    @Override
    public ComponentHealth health(String componentName) {
        return lifecycle.health(componentName);
    }
}
