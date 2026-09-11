package com.zsumz.logyard.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ScopeLoggerCacheTest {
    @Test
    void retainsOneScopeLoggerPerName() {
        try (SdkLoggerProvider provider = SdkLoggerProvider.builder().build()) {
            ScopeLoggerCache cache = new ScopeLoggerCache(provider, 8);
            Logger orders = cache.loggerFor("com.example.Orders");

            assertSame(orders, cache.loggerFor("com.example.Orders"));
            assertNotSame(orders, cache.loggerFor("com.example.Shipping"));
            assertEquals(2, cache.size());
        }
    }

    @Test
    void stopsRetainingScopesAtTheConfiguredCapacity() {
        try (SdkLoggerProvider provider = SdkLoggerProvider.builder().build()) {
            ScopeLoggerCache cache = new ScopeLoggerCache(provider, 4);
            for (int index = 0; index < 64; index++) {
                cache.loggerFor("com.example.Logger" + index);
            }
            assertEquals(4, cache.size());

            cache.clear();
            assertEquals(0, cache.size());
        }
    }

    @Test
    void usesTheFallbackScopeForUnnamedLoggers() {
        try (SdkLoggerProvider provider = SdkLoggerProvider.builder().build()) {
            ScopeLoggerCache cache = new ScopeLoggerCache(provider, 4);
            Logger fallback = cache.loggerFor(null);

            assertSame(fallback, cache.loggerFor(""));
            assertSame(fallback, provider.get(ScopeLoggerCache.FALLBACK_SCOPE));
            assertEquals(0, cache.size());
        }
    }

    @Test
    void rejectsACapacityOutsideItsPublishedBound() {
        try (SdkLoggerProvider provider = SdkLoggerProvider.builder().build()) {
            assertThrows(IllegalArgumentException.class, () -> new ScopeLoggerCache(provider, 0));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ScopeLoggerCache(provider, ScopeLoggerCache.MAX_SCOPES + 1));
        }
    }

    @Test
    void emitsThroughTheSharedFallbackScopeOnceTheCacheIsSaturated() {
        InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider provider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            OtelLogRecordSink sink = new OtelLogRecordSink(provider, 2);
            sink.accept(event("com.example.One"));
            sink.accept(event("com.example.Two"));
            sink.accept(event("com.example.Overflow"));

            List<LogRecordData> records = exporter.getFinishedLogRecordItems();
            assertEquals("com.example.One", records.get(0).getInstrumentationScopeInfo().getName());
            assertEquals("com.example.Two", records.get(1).getInstrumentationScopeInfo().getName());

            LogRecordData overflow = records.get(2);
            assertEquals(ScopeLoggerCache.FALLBACK_SCOPE, overflow.getInstrumentationScopeInfo().getName());
            assertEquals(
                    "com.example.Overflow",
                    overflow.getAttributes().get(AttributeKey.stringKey("logger.name")));
            assertEquals(2, sink.scopeCount());

            sink.close();
            assertEquals(0, sink.scopeCount());
        }
    }

    private static LogEvent event(String loggerName) {
        return new LogEvent(
                1_764_000_000_000L,
                1_764_000_000_000_000_000L,
                Level.INFO,
                loggerName,
                null,
                "ready",
                new Object[0],
                AttributeSet.EMPTY,
                null,
                7L,
                "scope-thread");
    }
}
