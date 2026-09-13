package com.logyard4j.logyard.slf4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.Logyard;
import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.routing.RouteDefinition;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.logyard.core.runtime.RuntimePlan;
import com.logyard4j.logyard.slf4j.internal.context.ContextSnapshotPolicy;
import com.logyard4j.logyard.slf4j.internal.context.LogyardMdcAdapter;
import com.logyard4j.logyard.slf4j.internal.event.Slf4jEventMapper;
import com.logyard4j.logyard.slf4j.internal.factory.LogyardLoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;
import org.slf4j.event.LoggingEvent;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.LocationAwareLogger;
import org.slf4j.spi.LoggingEventAware;

final class Slf4jProviderBehaviorTest {
    @Test
    void conventionalAndFluentCallsPreserveStructureContextAndMarkers() {
        RecordingSink sink = new RecordingSink();
        try (LogyardRuntime runtime = runtime(sink)) {
            LogyardMdcAdapter mdc = new LogyardMdcAdapter();
            mdc.put("request.id", "mdc-request");
            mdc.put("tenant.id", "tenant-7");
            mdc.put("ignored", "not-captured");
            Logger logger = new LogyardLoggerFactory(
                    runtime,
                    new Slf4jEventMapper(
                            mdc,
                            new ContextSnapshotPolicy(List.of("request.id", "tenant.id"))))
                    .getLogger("test.Service");
            assertFalse(logger instanceof LocationAwareLogger);

            logger.info("accepted order {}", 7);

            BasicMarkerFactory markerFactory = new BasicMarkerFactory();
            Marker root = markerFactory.getMarker("AUDIT");
            root.add(markerFactory.getMarker("ORDER"));
            IllegalStateException failure = new IllegalStateException("payment rejected");
            logger.atWarn()
                    .setMessage("order {} failed")
                    .addArgument(7)
                    .addMarker(root)
                    .addKeyValue("event.name", "order.failed")
                    .addKeyValue("request.id", "explicit-request")
                    .addKeyValue("order.id", 7L)
                    .setCause(failure)
                    .log();

            assertEquals(2, sink.events.size());
            LogEvent conventional = sink.events.get(0);
            assertEquals("accepted order {}", conventional.messageTemplate());
            assertEquals(7, conventional.argumentAt(0));
            assertEquals("mdc-request", conventional.attributes().get("request.id"));
            assertEquals("tenant-7", conventional.attributes().get("tenant.id"));
            assertEquals(null, conventional.attributes().get("ignored"));

            LogEvent fluent = sink.events.get(1);
            assertEquals("order.failed", fluent.eventName());
            assertEquals("order {} failed", fluent.messageTemplate());
            assertEquals("explicit-request", fluent.attributes().get("request.id"));
            assertEquals(7L, fluent.attributes().get("order.id"));
            assertEquals(List.of("AUDIT", "ORDER"), fluent.attributes().get("slf4j.markers"));
            assertTrue(fluent.timestampMillis() > 0L);
            assertEquals(Thread.currentThread().getName(), fluent.threadName());
            assertNotNull(fluent.exception());
            assertEquals("payment rejected", fluent.exception().message());
        }
    }

    @Test
    void loggingEventAwarePathPreservesSourceTimestampAndThreadName() {
        RecordingSink sink = new RecordingSink();
        try (LogyardRuntime runtime = runtime(sink)) {
            Logger logger = new LogyardLoggerFactory(
                    runtime,
                    new Slf4jEventMapper(
                            new LogyardMdcAdapter(),
                            new ContextSnapshotPolicy(List.of())))
                    .getLogger("test.ExternalEvent");
            LoggingEvent source = new ExternalLoggingEvent(
                    "test.ExternalEvent",
                    "external {} event",
                    new Object[] {"facade"},
                    List.of(
                            new KeyValuePair(null, "invalid"),
                            new KeyValuePair("event.name", "external.event"),
                            new KeyValuePair("answer", 42L)),
                    1_234_567_890L,
                    "logical-worker");

            ((LoggingEventAware) logger).log(source);
            LogEvent captured = sink.events.getFirst();
            assertEquals(1_234_567_890L, captured.timestampMillis());
            assertEquals("logical-worker", captured.threadName());
            assertEquals(-1L, captured.threadId());
            assertEquals("external.event", captured.eventName());
            assertEquals("external {} event", captured.messageTemplate());
            assertEquals("facade", captured.argumentAt(0));
            assertEquals(42L, captured.attributes().get("answer"));
            assertEquals(1, captured.attributes().get("logyard.slf4j.invalid_key_values"));
        }
    }

    @Test
    void disabledFluentSuppliersRemainDormant() {
        RecordingSink sink = new RecordingSink();
        try (LogyardRuntime runtime = runtime(sink)) {
            Logger logger = new LogyardLoggerFactory(
                    runtime,
                    new Slf4jEventMapper(
                            new LogyardMdcAdapter(),
                            new ContextSnapshotPolicy(List.of())))
                    .getLogger("test.Disabled");
            AtomicBoolean evaluated = new AtomicBoolean();
            logger.atDebug()
                    .addArgument(() -> {
                        evaluated.set(true);
                        return "expensive";
                    })
                    .log("disabled {} event");
            assertFalse(evaluated.get());
            assertTrue(sink.events.isEmpty());
        }
    }

    @Test
    void preservesCaptureTruncationFromMdcAndFluentAttributes() {
        RecordingSink sink = new RecordingSink();
        try (LogyardRuntime runtime = runtime(sink)) {
            LogyardMdcAdapter mdc = new LogyardMdcAdapter();
            mdc.put("oversized.mdc", "x".repeat(CaptureLimits.MAX_TEXT_CHARS + 1));
            Logger logger = new LogyardLoggerFactory(
                    runtime,
                    new Slf4jEventMapper(mdc, new ContextSnapshotPolicy(List.of("oversized.mdc"))))
                    .getLogger("test.Truncation");
            logger.info("mdc");
            logger.atInfo()
                    .addKeyValue("x".repeat(1_000) + ".authorization", "secret")
                    .log("fluent");

            assertEquals(true, sink.events.get(0).attributes().get("logyard.capture.truncated"));
            assertEquals(true, sink.events.get(1).attributes().get("logyard.capture.truncated"));
        }
    }

    @Test
    void enabledEventWithoutMetadataReusesTheSharedEmptyAttributeSet() {
        RecordingSink sink = new RecordingSink();
        try (LogyardRuntime runtime = runtime(sink)) {
            Logger logger = new LogyardLoggerFactory(
                    runtime,
                    new Slf4jEventMapper(
                            new LogyardMdcAdapter(),
                            new ContextSnapshotPolicy(List.of())))
                    .getLogger("test.EmptyAttributes");

            logger.info("plain event");

            assertSame(AttributeSet.EMPTY, sink.events.getFirst().attributes());
        }
    }

    @Test
    void serviceProviderBorrowsExistingRuntimeAndInitializesIdempotently() {
        RecordingSink sink = new RecordingSink();
        LogyardRuntime runtime = runtime(sink);
        Logyard.initialize(runtime);
        try {
            LogyardServiceProvider provider = new LogyardServiceProvider();
            assertThrows(
                    IllegalStateException.class,
                    () -> provider.getLoggerFactory().getLogger("before.initialize"));
            provider.initialize();
            provider.initialize();
            assertEquals("2.0.99", provider.getRequestedApiVersion());
            assertNotNull(provider.getMarkerFactory());
            assertTrue(provider.getMDCAdapter() instanceof LogyardMdcAdapter);
            assertFalse(provider.ownsRuntime());
            Logger first = provider.getLoggerFactory().getLogger("test.Provider");
            Logger second = provider.getLoggerFactory().getLogger("test.Provider");
            assertSame(first, second);
            first.info("provider event");
            assertEquals(1, sink.events.size());
            assertTrue(Logyard.isInitialized());
        } finally {
            Logyard.shutdown();
        }
    }

    private static LogyardRuntime runtime(EventSink sink) {
        return new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("capture"), List.of()),
                Map.of(),
                Map.of("capture", sink),
                Map.of()));
    }


    private static final class ExternalLoggingEvent implements LoggingEvent {
        private final String loggerName;
        private final String message;
        private final Object[] arguments;
        private final List<KeyValuePair> keyValues;
        private final long timestamp;
        private final String threadName;

        private ExternalLoggingEvent(
                String loggerName,
                String message,
                Object[] arguments,
                List<KeyValuePair> keyValues,
                long timestamp,
                String threadName) {
            this.loggerName = loggerName;
            this.message = message;
            this.arguments = arguments.clone();
            this.keyValues = List.copyOf(keyValues);
            this.timestamp = timestamp;
            this.threadName = threadName;
        }

        @Override public org.slf4j.event.Level getLevel() {
            return org.slf4j.event.Level.INFO;
        }
        @Override public String getLoggerName() { return loggerName; }
        @Override public String getMessage() { return message; }
        @Override public List<Object> getArguments() { return List.of(arguments); }
        @Override public Object[] getArgumentArray() { return arguments.clone(); }
        @Override public List<Marker> getMarkers() { return List.of(); }
        @Override public List<KeyValuePair> getKeyValuePairs() { return keyValues; }
        @Override public Throwable getThrowable() { return null; }
        @Override public long getTimeStamp() { return timestamp; }
        @Override public String getThreadName() { return threadName; }
    }

    private static final class RecordingSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();

        @Override
        public void accept(LogEvent event) {
            events.add(event);
        }
    }
}
