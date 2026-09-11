package com.logyard4j.examples.opentelemetry;

import com.logyard4j.opentelemetry.LogyardOpenTelemetry;
import com.logyard4j.runtime.bootstrap.LogyardBootstrap;
import com.logyard4j.runtime.bootstrap.LogyardConfigurationSource;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class OpenTelemetryLogsTest {
    @Test
    void exportsCapturedRecordsThroughTheApplicationsSdk() {
        var exporter = InMemoryLogRecordExporter.create();
        var provider = SdkLoggerProvider.builder()
                .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "checkout")))
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter)).build();
        try (var sdk = OpenTelemetrySdk.builder().setLoggerProvider(provider).build()) {
            LogyardOpenTelemetry.install(sdk);
            var source = LogyardConfigurationSource.text("OpenTelemetry Logs example", """
                    schema = 1
                    [context]
                    trace = true
                    [outputs.telemetry]
                    type = "custom"
                    provider = "otel"
                    [loggers]
                    root = { level = "info", outputs = ["telemetry"] }
                    """, Path.of("."));
            try (var logyard = LogyardBootstrap.start(source)) {
                var log = logyard.runtime().logger("checkout");
                var trace = SpanContext.create("a".repeat(32), "b".repeat(16),
                        TraceFlags.getSampled(), TraceState.getDefault());
                try (var scope = Context.root().with(Span.wrap(trace)).makeCurrent()) {
                    log.atInfo().event("order.accepted").add("attempt", 2)
                            .add("payload", Map.of("items", List.of("one", "two"))).log("order accepted");
                }
                log.info("outside request");
            }
            var flushed = provider.forceFlush().join(5, TimeUnit.SECONDS);
            assertTrue(flushed.isSuccess(), "application SDK flush must complete");
            var records = exporter.getFinishedLogRecordItems();
            assertEquals(2, records.size());
            var order = records.getFirst();
            assertEquals("order.accepted", order.getEventName());
            assertEquals("order accepted", order.getBodyValue().asString());
            assertEquals("a".repeat(32), order.getSpanContext().getTraceId());
            assertEquals("checkout", order.getResource().getAttribute(AttributeKey.stringKey("service.name")));
            assertEquals(2L, order.getAttributes().get(AttributeKey.longKey("attempt")));
            assertEquals(Value.of(Map.of("items", Value.of(Value.of("one"), Value.of("two")))),
                    order.getAttributes().get(AttributeKey.valueKey("payload")));
            assertFalse(records.getLast().getSpanContext().isValid());

            // The application owns one live SDK across managed runtime restarts.
            try (var replacement = OpenTelemetrySdk.builder().build()) {
                assertThrows(IllegalStateException.class, () -> LogyardOpenTelemetry.install(replacement));
            }
            LogyardOpenTelemetry.install(sdk);
            try (var restarted = LogyardBootstrap.start(source)) {
                var log = restarted.runtime().logger("checkout");
                log.info("runtime restarted");
                log.info("restart close-time drain");
            }
            assertTrue(provider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess());
            records = exporter.getFinishedLogRecordItems();
            assertEquals(4, records.size());
            assertEquals("runtime restarted", records.get(2).getBodyValue().asString());
            assertEquals("restart close-time drain", records.getLast().getBodyValue().asString());
            provider.get("application").logRecordBuilder().setBody("SDK remains application-owned").emit();
            assertEquals(5, exporter.getFinishedLogRecordItems().size());
        }
    }
}
