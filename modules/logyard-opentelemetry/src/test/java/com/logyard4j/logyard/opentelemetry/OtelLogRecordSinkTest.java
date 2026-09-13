package com.logyard4j.logyard.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class OtelLogRecordSinkTest {
    private static final long TIMESTAMP_MILLIS = 1_764_000_000_123L;
    private static final long OBSERVED_NANOS = 1_764_000_000_456_000_000L;

    private final InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
    private SdkLoggerProvider loggerProvider;
    private OtelLogRecordSink sink;

    @BeforeEach
    void start() {
        loggerProvider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build();
        sink = new OtelLogRecordSink(loggerProvider);
    }

    @AfterEach
    void stop() {
        sink.close();
        loggerProvider.close();
    }

    @Test
    void mapsIdentityTimestampsSeverityAndBody() {
        LogEvent event = event(Level.WARN, "com.example.Orders", "order.created", AttributeSet.EMPTY, null);
        sink.accept(event);

        LogRecordData data = single();
        assertEquals(Severity.WARN, data.getSeverity());
        assertEquals("WARN", data.getSeverityText());
        assertEquals(event.renderedMessage(), data.getBodyValue().asString());
        assertEquals(TIMESTAMP_MILLIS * 1_000_000L, data.getTimestampEpochNanos());
        assertEquals(OBSERVED_NANOS, data.getObservedTimestampEpochNanos());
        assertEquals("order.created", data.getEventName());
        assertEquals("com.example.Orders", data.getInstrumentationScopeInfo().getName());
        Attributes attributes = data.getAttributes();
        assertEquals("com.example.Orders", attributes.get(AttributeKey.stringKey("logger.name")));
        assertEquals("bridge-thread", attributes.get(AttributeKey.stringKey("thread.name")));
        assertEquals(Long.valueOf(41L), attributes.get(AttributeKey.longKey("thread.id")));
    }

    @Test
    void omitsAnEventNameThatWasNeverSet() {
        sink.accept(event(Level.INFO, "com.example.Orders", null, AttributeSet.EMPTY, null));
        assertNull(single().getEventName());
    }

    @Test
    void mapsAttributeValuesOntoTheirNativeTypes() {
        AttributeSet source = AttributeSet.builder()
                .put("tenant", "acme")
                .put("order.count", 7L)
                .put("amount", 19.5d)
                .put("retry", true)
                .put("attempt", 3)
                .build();
        sink.accept(event(Level.INFO, "com.example.Orders", null, source, null));

        Attributes attributes = single().getAttributes();
        assertEquals("acme", attributes.get(AttributeKey.stringKey("tenant")));
        assertEquals(Long.valueOf(7L), attributes.get(AttributeKey.longKey("order.count")));
        assertEquals(Double.valueOf(19.5d), attributes.get(AttributeKey.doubleKey("amount")));
        assertEquals(Boolean.TRUE, attributes.get(AttributeKey.booleanKey("retry")));
        assertEquals(Long.valueOf(3L), attributes.get(AttributeKey.longKey("attempt")));
    }

    @Test
    void mapsTheCapturedExceptionOntoSemanticConventionAttributes() {
        IllegalStateException failure = new IllegalStateException("order rejected", new IOException("socket"));
        sink.accept(event(Level.ERROR, "com.example.Orders", null, AttributeSet.EMPTY, failure));

        Attributes attributes = single().getAttributes();
        assertEquals(IllegalStateException.class.getName(), attributes.get(AttributeKey.stringKey("exception.type")));
        assertEquals("order rejected", attributes.get(AttributeKey.stringKey("exception.message")));
        String stackTrace = attributes.get(AttributeKey.stringKey("exception.stacktrace"));
        assertTrue(stackTrace.startsWith("java.lang.IllegalStateException: order rejected"), stackTrace);
        assertTrue(stackTrace.contains("\n\tat "), stackTrace);
        assertTrue(stackTrace.contains("Caused by: java.io.IOException: socket"), stackTrace);
    }

    @Test
    void logyardIdentityAttributesOutrankApplicationAttributesOfTheSameName() {
        AttributeSet source = AttributeSet.of("logger.name", "spoofed");
        sink.accept(event(Level.INFO, "com.example.Orders", null, source, null));

        assertEquals("com.example.Orders", single().getAttributes().get(AttributeKey.stringKey("logger.name")));
    }

    @Test
    void alternateAttributeTypesCannotShadowOwnedIdentityAndExceptionFields() {
        AttributeSet source = AttributeSet.builder().put("logger.name", 42L)
                .put("thread.name", true).put("thread.id", "spoofed")
                .put("exception.type", false).put("exception.message", 7L).build();
        sink.accept(event(Level.INFO, "test", null, source, new IllegalStateException("actual")));
        Attributes attributes = single().getAttributes();
        assertNull(attributes.get(AttributeKey.longKey("logger.name")));
        assertNull(attributes.get(AttributeKey.booleanKey("thread.name")));
        assertNull(attributes.get(AttributeKey.stringKey("thread.id")));
        assertNull(attributes.get(AttributeKey.booleanKey("exception.type")));
        assertNull(attributes.get(AttributeKey.longKey("exception.message")));
        assertEquals("test", attributes.get(AttributeKey.stringKey("logger.name")));
        assertEquals("actual", attributes.get(AttributeKey.stringKey("exception.message")));
    }

    @Test
    void restoresCapturedTraceIdentityOntoTheRecord() {
        AttributeSet source = AttributeSet.builder()
                .put("trace_id", "0af7651916cd43dd8448eb211c80319c")
                .put("span_id", "b7ad6b7169203331")
                .put("trace_flags", "01")
                .build();
        sink.accept(event(Level.INFO, "com.example.Orders", null, source, null));

        LogRecordData data = single();
        assertEquals("0af7651916cd43dd8448eb211c80319c", data.getSpanContext().getTraceId());
        assertEquals("b7ad6b7169203331", data.getSpanContext().getSpanId());
        assertTrue(data.getSpanContext().isSampled());
    }

    @Test
    void leavesTheRecordUncorrelatedWhenCapturedTraceIdentityIsUnusable() {
        AttributeSet source = AttributeSet.builder().put("trace_id", "not-hexadecimal").put("span_id", "b7ad6b7169203331").build();
        sink.accept(event(Level.INFO, "com.example.Orders", null, source, null));

        assertFalse(single().getSpanContext().isValid());
    }

    @Test
    void usesOneInstrumentationScopePerLoggerName() {
        sink.accept(event(Level.INFO, "com.example.Orders", null, AttributeSet.EMPTY, null));
        sink.accept(event(Level.INFO, "com.example.Shipping", null, AttributeSet.EMPTY, null));

        List<LogRecordData> records = exporter.getFinishedLogRecordItems();
        assertEquals(2, records.size());
        assertEquals("com.example.Orders", records.get(0).getInstrumentationScopeInfo().getName());
        assertEquals("com.example.Shipping", records.get(1).getInstrumentationScopeInfo().getName());
        assertEquals(2, sink.scopeCount());
    }

    private LogRecordData single() {
        List<LogRecordData> records = exporter.getFinishedLogRecordItems();
        assertEquals(1, records.size());
        return records.get(0);
    }

    private static LogEvent event(
            Level level, String loggerName, String eventName, AttributeSet attributes, Throwable throwable) {
        return new LogEvent(
                TIMESTAMP_MILLIS,
                OBSERVED_NANOS,
                level,
                loggerName,
                eventName,
                "order {} for {}",
                new Object[] {"A1", 42L},
                attributes,
                throwable,
                41L,
                "bridge-thread");
    }
}
