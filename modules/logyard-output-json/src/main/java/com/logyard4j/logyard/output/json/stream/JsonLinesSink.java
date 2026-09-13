package com.logyard4j.logyard.output.json.stream;

import com.logyard4j.logyard.api.annotation.InternalApi;
import com.logyard4j.logyard.api.diagnostics.ComponentHealth;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.diagnostics.HealthContributor;
import com.logyard4j.logyard.api.spi.encoding.EventEncoder;
import com.logyard4j.logyard.api.spi.encoding.EventEncoderBoundary;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.output.json.encoding.JsonEncoder;
import com.logyard4j.logyard.output.json.flush.FlushScheduler;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Thread-safe JSONL sink with bounded, time-based flushing.
 *
 * <p>Encoding happens outside the transport monitor. Concurrent records are written atomically
 * with unspecified relative order, which keeps extension callbacks free to invoke other sink methods.</p>
 *
 * <p>Caller-supplied writers and streams must not recursively invoke lifecycle methods such as
 * {@link #flush()} or {@link #close()} on this same sink. Reentrant transport callbacks are unsupported.</p>
 */
public final class JsonLinesSink implements EventSink, HealthContributor {
    private final JsonStreamLifecycle<?> lifecycle;
    private final Consumer<LogEvent> delivery;

    public JsonLinesSink(Writer writer, EventEncoder encoder, Duration flushInterval, boolean closeWriter) {
        this(writer, encoder, flushInterval, closeWriter, FlushScheduler.shared());
    }

    JsonLinesSink(Writer writer, EventEncoder encoder, Duration flushInterval,
            boolean closeWriter, FlushScheduler scheduler) {
        Objects.requireNonNull(writer, "writer");
        EventEncoder guarded = EventEncoderBoundary.guard(encoder);
        JsonStreamLifecycle<String> target = new JsonStreamLifecycle<>(
                writer, flushInterval, closeWriter, scheduler, (record, length) -> {
                    writer.write(record);
                    writer.write('\n');
                });
        lifecycle = target;
        delivery = event -> {
            String record = guarded.encode(event);
            target.write(record, record.length());
        };
    }

    private JsonLinesSink(JsonStreamLifecycle<?> target, Consumer<LogEvent> delivery) {
        lifecycle = target;
        this.delivery = delivery;
    }

    /**
     * Creates a buffered UTF-8 output, using reusable record storage for the built-in JSON encoder.
     *
     * @param output destination byte stream
     * @param encoder built-in JSON or public text encoder SPI
     * @param flushInterval maximum buffering interval
     * @param closeOutput whether closing this sink closes the supplied stream
     * @return serialized JSONL output
     * @hidden
     */
    @InternalApi
    public static JsonLinesSink bytes(OutputStream output, EventEncoder encoder,
            Duration flushInterval, boolean closeOutput) {
        return bytes(output, encoder, flushInterval, closeOutput, FlushScheduler.shared());
    }

    static JsonLinesSink bytes(OutputStream output, EventEncoder encoder,
            Duration flushInterval, boolean closeOutput, FlushScheduler scheduler) {
        Objects.requireNonNull(output, "output");
        if (!(encoder instanceof JsonEncoder json)) {
            return new JsonLinesSink(new OutputStreamWriter(output, StandardCharsets.UTF_8),
                    encoder, flushInterval, closeOutput, scheduler);
        }
        BufferedOutputStream buffered = new BufferedOutputStream(output, 8 * 1_024);
        JsonStreamLifecycle<byte[]> target = new JsonStreamLifecycle<>(
                buffered, flushInterval, closeOutput, scheduler, (record, length) -> {
                    buffered.write(record, 0, length);
                    buffered.write('\n');
                });
        return new JsonLinesSink(target, json.utf8Records(target::write));
    }

    @Override
    public void accept(LogEvent event) {
        lifecycle.requireOpen();
        delivery.accept(Objects.requireNonNull(event, "event"));
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
