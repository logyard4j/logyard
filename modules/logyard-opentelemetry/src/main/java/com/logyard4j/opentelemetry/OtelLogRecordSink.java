package com.logyard4j.opentelemetry;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.output.EventSink;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.LoggerProvider;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Forwards accepted events into the OpenTelemetry Logs API.
 *
 * <p>The sink holds only the application-supplied {@link LoggerProvider}, so the application keeps
 * ownership of its SDK, processors, exporters, and resource. Nothing here starts a transport of its
 * own.</p>
 *
 * <p>{@link OtelOutputProvider} resolves that provider once, when it creates this sink, using
 * the instance passed to {@link LogyardOpenTelemetry#install}. A sink therefore keeps publishing to
 * the logs bridge it was built with until a configuration reload replaces it.</p>
 */
final class OtelLogRecordSink implements EventSink {
    private final ScopeLoggerCache scopes;
    private volatile Phase phase = Phase.OPEN;

    OtelLogRecordSink(LoggerProvider loggerProvider) {
        this(loggerProvider, ScopeLoggerCache.MAX_SCOPES);
    }

    OtelLogRecordSink(LoggerProvider loggerProvider, int maxScopes) {
        scopes = new ScopeLoggerCache(loggerProvider, maxScopes);
    }

    @Override
    public void accept(LogEvent event) {
        Objects.requireNonNull(event, "event");
        if (phase == Phase.CLOSED) throw new IllegalStateException("otel output is closed");
        LogRecordBuilder builder = scopes.loggerFor(event.loggerName()).logRecordBuilder();
        builder.setTimestamp(
                TimeUnit.MILLISECONDS.toNanos(event.timestampMillis()), TimeUnit.NANOSECONDS);
        builder.setObservedTimestamp(event.observedTimestampUnixNanos(), TimeUnit.NANOSECONDS);
        Level level = event.level();
        builder.setSeverity(OtelSeverity.of(level));
        builder.setSeverityText(level.name());
        builder.setBody(event.renderedMessage());
        if (event.eventName() != null) {
            builder.setEventName(event.eventName());
        }
        OtelTraceContext.apply(builder, event.attributes());
        OtelLogRecordAttributes.apply(builder, event);
        builder.emit();
    }

    /** The Logs API has no flush operation; the application must flush its SDK. */
    @Override
    public void flush() {
    }

    /** Releases the scope-logger cache and leaves the application-owned SDK untouched. */
    @Override
    public void close() {
        phase = Phase.CLOSED;
        scopes.clear();
    }

    /**
     * Returns the number of retained instrumentation scopes.
     *
     * @return retained scope logger count
     */
    int scopeCount() {
        return scopes.size();
    }
    private enum Phase { OPEN, CLOSED }
}
