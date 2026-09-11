package com.zsumz.logyard.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.config.ProviderConfiguration;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.api.spi.output.OutputProviderContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers explicit SDK installation and the logs-bridge resolution it feeds, including the
 * {@code GlobalOpenTelemetry} latching order that installation exists to defeat.
 */
final class LogyardOpenTelemetryTest {
    private final InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
    private final OtelOutputProvider provider = new OtelOutputProvider();
    private SdkLoggerProvider loggerProvider;
    private OpenTelemetrySdk sdk;

    @BeforeEach
    void start() {
        LogyardOpenTelemetry.reset();
        GlobalOpenTelemetry.resetForTest();
        loggerProvider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build();
        sdk = OpenTelemetrySdk.builder().setLoggerProvider(loggerProvider).build();
    }

    @AfterEach
    void stop() {
        LogyardOpenTelemetry.reset();
        GlobalOpenTelemetry.resetForTest();
        loggerProvider.close();
    }

    @Test
    void createdOutputPublishesThroughTheInstalledInstance() {
        LogyardOpenTelemetry.install(sdk);

        try (EventSink sink = create()) {
            sink.accept(event());
        }

        LogRecordData data = single();
        assertEquals("checkout started", data.getBodyValue().asString());
        assertEquals("com.example.Checkout", data.getInstrumentationScopeInfo().getName());
    }

    @Test
    void installationSurvivesAGlobalThatWasAlreadyLatchedToNoOp() {
        assertSame(GlobalOpenTelemetry.get(), GlobalOpenTelemetry.get());
        assertThrows(IllegalStateException.class, () -> GlobalOpenTelemetry.set(sdk));

        LogyardOpenTelemetry.install(sdk);
        try (EventSink sink = create()) {
            sink.accept(event());
        }

        assertEquals("checkout started", single().getBodyValue().asString());
    }

    @Test
    void theInstalledInstanceOutranksADifferentGlobalInstance() {
        InMemoryLogRecordExporter globalExporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider globalLoggers = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(globalExporter))
                .build()) {
            GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setLoggerProvider(globalLoggers).build());
            LogyardOpenTelemetry.install(sdk);

            try (EventSink sink = create()) {
                sink.accept(event());
            }

            assertEquals("checkout started", single().getBodyValue().asString());
            assertEquals(List.of(), globalExporter.getFinishedLogRecordItems());
        }
    }

    @Test
    void missingInstallationFailsWithoutLatchingTheOpenTelemetryGlobal() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, this::create);
        assertTrue(failure.getMessage().contains("LogyardOpenTelemetry.install"));
        GlobalOpenTelemetry.set(sdk);
        assertNull(LogyardOpenTelemetry.installedOrNull());
    }

    @Test
    void explicitInstallationIsRequiredEvenIfAGlobalSdkExists() {
        GlobalOpenTelemetry.set(sdk);
        assertThrows(IllegalStateException.class, this::create);
        LogyardOpenTelemetry.install(sdk);
        try (EventSink sink = create()) {
            sink.accept(event());
        }
        assertEquals("checkout started", single().getBodyValue().asString());
    }

    @Test
    void rejectsUnknownProviderOptions() {
        LogyardOpenTelemetry.install(sdk);
        assertThrows(IllegalArgumentException.class, () -> provider.create(
                new OutputProviderContext("otel", AttributeSet.EMPTY, Duration.ofSeconds(5), null, null),
                new ProviderConfiguration(java.util.Map.of("endpoint", "unexpected"))));
    }

    @Test
    void closingTheSinkLeavesTheSdkUsableAndRejectsFurtherRecords() {
        LogyardOpenTelemetry.install(sdk);
        EventSink sink = create();
        sink.close();
        sink.close();
        assertThrows(IllegalStateException.class, () -> sink.accept(event()));
        loggerProvider.get("application").logRecordBuilder().setBody("SDK still open").emit();
        assertEquals("SDK still open", single().getBodyValue().asString());
    }

    @Test
    void reinstallingADifferentInstanceIsRejected() {
        LogyardOpenTelemetry.install(sdk);
        OpenTelemetrySdk replacement = OpenTelemetrySdk.builder().build();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> LogyardOpenTelemetry.install(replacement));

        assertTrue(failure.getMessage().contains("already installed"), failure.getMessage());
        assertSame(sdk, LogyardOpenTelemetry.installedOrNull());
        replacement.close();
    }

    @Test
    void reinstallingTheSameInstanceIsANoOp() {
        LogyardOpenTelemetry.install(sdk);
        LogyardOpenTelemetry.install(sdk);

        assertSame(sdk, LogyardOpenTelemetry.installedOrNull());
        try (EventSink sink = create()) {
            sink.accept(event());
        }
        assertEquals(1, exporter.getFinishedLogRecordItems().size());
    }

    @Test
    void installationRejectsANullInstance() {
        assertThrows(NullPointerException.class, () -> LogyardOpenTelemetry.install(null));
        assertNull(LogyardOpenTelemetry.installedOrNull());
    }

    @Test
    void resetClearsTheSlotSoTheNextTestInstallsFreely() {
        LogyardOpenTelemetry.install(sdk);
        LogyardOpenTelemetry.reset();
        assertNull(LogyardOpenTelemetry.installedOrNull());

        OpenTelemetrySdk replacement = OpenTelemetrySdk.builder().build();
        LogyardOpenTelemetry.install(replacement);
        assertSame(replacement, LogyardOpenTelemetry.installedOrNull());
        replacement.close();
    }

    private EventSink create() {
        return provider.create(
                new OutputProviderContext(
                        "otel", AttributeSet.EMPTY, Duration.ofSeconds(5), null, null),
                ProviderConfiguration.EMPTY);
    }

    private LogRecordData single() {
        List<LogRecordData> records = exporter.getFinishedLogRecordItems();
        assertEquals(1, records.size());
        return records.get(0);
    }

    private static LogEvent event() {
        return new LogEvent(
                1_764_000_000_123L,
                1_764_000_000_456_000_000L,
                Level.INFO,
                "com.example.Checkout",
                null,
                "checkout started",
                new Object[0],
                AttributeSet.EMPTY,
                null,
                41L,
                "bridge-thread");
    }
}
