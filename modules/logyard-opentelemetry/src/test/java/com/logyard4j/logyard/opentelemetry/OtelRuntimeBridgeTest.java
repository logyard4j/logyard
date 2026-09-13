package com.logyard4j.logyard.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.api.context.ContextScope;
import com.logyard4j.logyard.api.context.LogContext;
import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.config.loading.LogyardConfigLoader;
import com.logyard4j.logyard.runtime.assembly.LogyardRuntimeFactory;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives a real TOML-configured Logyard runtime whose only output is the {@code otel} provider,
 * so ServiceLoader discovery, the context SPI, and the custom-output path are all exercised.
 */
final class OtelRuntimeBridgeTest {
    private static final String CONFIG = """
            schema = 1
            [service]
            name = "otel-bridge-test"
            [runtime]
            watch = false
            internal_status = "off"
            [context]
            trace = true
            baggage = ["tenant.id"]
            [delivery]
            mode = "async"
            capacity = 64
            [loggers]
            root = { level = "info", outputs = ["otel"] }
            [outputs.otel]
            type = "custom"
            provider = "otel"
            """;

    private final InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
    private SdkLoggerProvider loggerProvider;
    private SdkTracerProvider tracerProvider;
    private OpenTelemetrySdk sdk;

    @BeforeEach
    void installSdk() {
        loggerProvider = SdkLoggerProvider.builder()
                .setResource(io.opentelemetry.sdk.resources.Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "sdk-owned-service")))
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build();
        tracerProvider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).build();
        sdk = OpenTelemetrySdk.builder()
                .setLoggerProvider(loggerProvider)
                .setTracerProvider(tracerProvider)
                .build();
        LogyardOpenTelemetry.reset();
        LogyardOpenTelemetry.install(sdk);
    }

    @AfterEach
    void removeSdk() {
        LogyardOpenTelemetry.reset();
        tracerProvider.close();
        loggerProvider.close();
    }

    @Test
    void routesConfiguredEventsThroughTheLogsApi() {
        LogyardConfig config = LogyardConfigLoader.parse(CONFIG, "otel.toml", Path.of("."), Map.of());
        try (LogyardRuntime runtime = LogyardRuntimeFactory.create(config)) {
            assertTrue(runtime.explain("com.example.Checkout").processors().contains("logyard-context"));
            runtime.logger("com.example.Checkout")
                    .atWarn()
                    .event("checkout.failed")
                    .add("order.id", "A1")
                    .cause(new IllegalStateException("declined"))
                    .log("checkout failed for {}", "acme");
            runtime.flush();
        }

        LogRecordData data = single();
        assertEquals(Severity.WARN, data.getSeverity());
        assertEquals("sdk-owned-service", data.getResource().getAttribute(AttributeKey.stringKey("service.name")));
        assertEquals("checkout.failed", data.getEventName());
        assertEquals("com.example.Checkout", data.getInstrumentationScopeInfo().getName());
        assertEquals("checkout failed for acme", data.getBodyValue().asString());
        Attributes attributes = data.getAttributes();
        assertEquals("A1", attributes.get(AttributeKey.stringKey("order.id")));
        assertEquals("com.example.Checkout", attributes.get(AttributeKey.stringKey("logger.name")));
        assertEquals(
                IllegalStateException.class.getName(), attributes.get(AttributeKey.stringKey("exception.type")));
    }

    @Test
    void carriesTraceIdentityBaggageAndScopedContextTogether() {
        LogyardConfig config = LogyardConfigLoader.parse(CONFIG, "otel.toml", Path.of("."), Map.of());
        SpanContext expected;
        try (LogyardRuntime runtime = LogyardRuntimeFactory.create(config)) {
            Span span = tracerProvider.get("test").spanBuilder("checkout").startSpan();
            expected = span.getSpanContext();
            try (Scope spanScope = span.makeCurrent();
                    Scope baggageScope = Baggage.builder().put("tenant.id", "acme").build().makeCurrent();
                    ContextScope scoped = LogContext.push("cart.id", "c-1")) {
                runtime.logger("com.example.Checkout").atInfo().log("checkout started");
            } finally {
                span.end();
            }
            runtime.flush();
        }

        LogRecordData data = single();
        Attributes attributes = data.getAttributes();
        assertEquals(expected.getTraceId(), attributes.get(AttributeKey.stringKey("trace_id")));
        assertEquals(expected.getSpanId(), attributes.get(AttributeKey.stringKey("span_id")));
        assertEquals(expected.getTraceFlags().asHex(), attributes.get(AttributeKey.stringKey("trace_flags")));
        assertEquals("acme", attributes.get(AttributeKey.stringKey("baggage.tenant.id")));
        assertEquals("c-1", attributes.get(AttributeKey.stringKey("cart.id")));
        assertEquals(expected.getTraceId(), data.getSpanContext().getTraceId());
        assertEquals(expected.getSpanId(), data.getSpanContext().getSpanId());
        assertTrue(data.getSpanContext().isSampled());
    }

    private LogRecordData single() {
        List<LogRecordData> records = exporter.getFinishedLogRecordItems();
        assertEquals(1, records.size());
        return records.get(0);
    }
}
