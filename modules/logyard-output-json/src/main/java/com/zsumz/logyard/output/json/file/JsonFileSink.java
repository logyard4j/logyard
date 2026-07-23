package com.zsumz.logyard.output.json.file;

import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.diagnostics.HealthStatus;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.EventEncoder;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.api.spi.HealthContributor;
import com.zsumz.logyard.output.json.encoding.JsonEncoder;
import com.zsumz.logyard.output.json.encoding.ResourceAttributes;
import com.zsumz.logyard.output.json.file.rotation.RotationPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Exclusive UTF-8 JSONL file output with optional record-boundary rotation. */
public final class JsonFileSink implements EventSink, HealthContributor {
    public static final int MIN_BUFFER_BYTES = 1_024;
    public static final int MAX_BUFFER_BYTES = 16 * 1_024 * 1_024;

    private final Path path;
    private final EventEncoder encoder;
    private final RotatingFileWriter writer;
    private final RotationPolicy rotationPolicy;
    private final long flushIntervalNanos;
    private long nextFlushNanos;
    private boolean closed;

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
        this(path,
                new JsonEncoder(Objects.requireNonNull(resource, "resource")),
                bufferBytes,
                flushInterval,
                append,
                rotationPolicy);
    }

    public JsonFileSink(
            Path path,
            EventEncoder encoder,
            int bufferBytes,
            Duration flushInterval,
            boolean append,
            RotationPolicy rotationPolicy) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(flushInterval, "flushInterval");
        if (flushInterval.isNegative()) {
            throw new IllegalArgumentException("flush interval must not be negative");
        }
        if (bufferBytes < MIN_BUFFER_BYTES || bufferBytes > MAX_BUFFER_BYTES) {
            throw new IllegalArgumentException(
                    "JSON buffer must be between " + MIN_BUFFER_BYTES + " and " + MAX_BUFFER_BYTES + " bytes");
        }
        this.rotationPolicy = rotationPolicy;
        flushIntervalNanos = saturatedNanos(flushInterval);
        nextFlushNanos = System.nanoTime() + flushIntervalNanos;
        writer = new RotatingFileWriter(this.path, bufferBytes, append, rotationPolicy);
    }

    public Path path() {
        return path;
    }

    public RotationPolicy rotationPolicy() {
        return rotationPolicy;
    }

    @Override
    public synchronized void accept(LogEvent event) {
        ensureOpen();
        byte[] json = encoder.encode(Objects.requireNonNull(event, "event")).getBytes(StandardCharsets.UTF_8);
        byte[] record = Arrays.copyOf(json, json.length + 1);
        record[record.length - 1] = (byte) '\n';
        writer.writeRecord(record);
        long now = System.nanoTime();
        if (flushIntervalNanos == 0 || now >= nextFlushNanos) {
            writer.flush();
            nextFlushNanos = now + flushIntervalNanos;
        }
    }

    @Override
    public synchronized void flush() {
        ensureOpen();
        writer.flush();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        writer.close();
    }

    @Override
    public synchronized ComponentHealth health(String componentName) {
        String failureType = writer.maintenanceFailureType();
        HealthStatus status;
        if (closed || writer.closed()) {
            status = HealthStatus.STOPPED;
        } else if (failureType != null || !writer.maintenanceWorkerAlive()) {
            status = HealthStatus.FAILED;
        } else if (writer.maintenanceClosing()) {
            status = HealthStatus.STOPPING;
        } else if (writer.maintenanceQueueCapacity() > 0
                && writer.maintenanceQueuedTasks() * 4L
                        >= writer.maintenanceQueueCapacity() * 3L) {
            status = HealthStatus.DEGRADED;
        } else {
            status = HealthStatus.HEALTHY;
        }
        Map<String, String> details = new LinkedHashMap<>();
        details.put("format", "jsonl");
        details.put("path", path.toString());
        details.put("rotation", Boolean.toString(rotationPolicy != null));
        if (failureType != null) {
            details.put("maintenance_failure", failureType);
        }
        Map<String, Long> metrics = Map.of(
                "maintenance_queue_capacity", (long) writer.maintenanceQueueCapacity(),
                "maintenance_queue_depth", (long) writer.maintenanceQueuedTasks());
        return new ComponentHealth(componentName, "json-file-output", status, details, metrics);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Logyard JSON output is closed: " + path);
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
