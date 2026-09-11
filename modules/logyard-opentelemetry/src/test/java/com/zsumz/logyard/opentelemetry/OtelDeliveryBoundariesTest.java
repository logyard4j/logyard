package com.zsumz.logyard.opentelemetry;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class OtelDeliveryBoundariesTest {
    @Test
    void ambientDeliveryContextCannotSupplyAMissingOrInvalidCapturedTrace() {
        var exporter = InMemoryLogRecordExporter.create();
        try (var provider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter)).build()) {
            var sink = new OtelLogRecordSink(provider);
            SpanContext ambient = SpanContext.create("a".repeat(32), "b".repeat(16),
                    TraceFlags.getSampled(), TraceState.getDefault());
            try (var scope = Span.wrap(ambient).makeCurrent()) {
                sink.accept(event(AttributeSet.EMPTY));
                sink.accept(event(AttributeSet.of("trace_id", "invalid")));
                sink.accept(event(AttributeSet.builder().put("trace_id", "c".repeat(32))
                        .put("span_id", "d".repeat(16)).put("trace_flags", "03").build()));
            }
            var records = exporter.getFinishedLogRecordItems();
            assertEquals(3, records.size());
            assertFalse(records.get(0).getSpanContext().isValid());
            assertFalse(records.get(1).getSpanContext().isValid());
            assertEquals("c".repeat(32), records.get(2).getSpanContext().getTraceId());
            assertEquals("03", records.get(2).getSpanContext().getTraceFlags().asHex());
        }
    }

    @Test
    void retainsNestedTypesNullAndExactDecimalText() {
        var exporter = InMemoryLogRecordExporter.create();
        try (var provider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter)).build()) {
            var sink = new OtelLogRecordSink(provider);
            sink.accept(event(AttributeSet.of("payload", Map.of("items", Arrays.asList(
                    1, true, null, Map.of("price", new BigDecimal("123456789.123456789")))))));
            var attributes = exporter.getFinishedLogRecordItems().getFirst().getAttributes();
            Value<?> expected = Value.of(Map.of("items", Value.of(List.of(
                    Value.of(1L), Value.of(true), Value.empty(),
                    Value.of(Map.of("price", Value.of("123456789.123456789")))))));
            assertEquals(expected, attributes.get(AttributeKey.valueKey("payload")));
        }
    }

    @Test
    void concurrentNewNamesCannotExceedTheScopeLimit() throws Exception {
        try (var provider = SdkLoggerProvider.builder().build();
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var cache = new ScopeLoggerCache(provider, 4);
            var start = new CountDownLatch(1);
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 128; index++) {
                String name = "logger-" + index;
                tasks.add(workers.submit(() -> {
                    start.await();
                    cache.loggerFor(name);
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get();
            assertEquals(4, cache.size());
        }
    }

    private static LogEvent event(AttributeSet attributes) {
        return new LogEvent(1_764_000_000_123L, 1_764_000_000_456_000_000L,
                Level.INFO, "test", null, "captured", new Object[0], attributes, null, 41L, "caller");
    }
}
