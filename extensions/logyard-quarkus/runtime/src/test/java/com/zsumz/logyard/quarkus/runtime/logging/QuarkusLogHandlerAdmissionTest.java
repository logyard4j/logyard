package com.zsumz.logyard.quarkus.runtime.logging;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.routing.RouteDefinition;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.core.runtime.RuntimePlan;
import com.zsumz.logyard.runtime.management.LoggerLevel;
import com.zsumz.logyard.runtime.management.LoggerLevelManagement;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Admission must see live levels, reject OFF, and preserve recursion and retirement boundaries. */
final class QuarkusLogHandlerAdmissionTest {
    @Test
    void admissionGuardsUserFiltersThatLogThroughTheSameHandler() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = runtime(sink, Level.INFO)) {
            QuarkusLogHandler handler = new QuarkusLogHandler(runtime);
            handler.setFilter(candidate -> {
                if (calls.incrementAndGet() < 4) {
                    handler.publish(record(java.util.logging.Level.INFO, "from filter"));
                }
                return true;
            });
            handler.publish(record(java.util.logging.Level.INFO, "outer"));
            handler.close();
        }
        assertEquals(1, calls.get());
        assertEquals(1, sink.events.size());
        assertEquals("outer", sink.events.getFirst().renderedMessage());
    }

    @Test
    void dropsRecordsBelowTheConfiguredLevel() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = runtime(sink, Level.ERROR)) {
            QuarkusLogHandler handler = new QuarkusLogHandler(runtime);

            handler.publish(record(java.util.logging.Level.INFO, "below the threshold"));
            handler.publish(record(java.util.logging.Level.SEVERE, "above the threshold"));
            handler.close();
        }

        assertEquals(1, sink.events.size());
        assertEquals("above the threshold", sink.events.getFirst().renderedMessage());
    }

    @Test
    void suppressesAnOffLevelRecordTheLogManagerStillDelivers() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = runtime(sink, Level.TRACE)) {
            QuarkusLogHandler handler = new QuarkusLogHandler(runtime);

            handler.publish(record(java.util.logging.Level.OFF, "never captured"));
            handler.publish(record(new ShadowOff(), "a custom OFF-valued level is suppressed too"));
            handler.close();
        }

        assertEquals(0, sink.events.size());
    }

    private static final class ShadowOff extends java.util.logging.Level {
        private static final long serialVersionUID = 1L;

        private ShadowOff() {
            super("SHADOW_OFF", java.util.logging.Level.OFF.intValue());
        }
    }

    @Test
    void dynamicLevelOverridesReachAnAlreadyInstalledHandler() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = runtime(sink, Level.INFO)) {
            QuarkusLogHandler handler = new QuarkusLogHandler(runtime);
            handler.publish(record(java.util.logging.Level.FINE, "before the override"));
            assertEquals(0, sink.events.size());

            LoggerLevelManagement.forRuntime(runtime).setLevel("quarkus.admission", LoggerLevel.DEBUG);
            handler.publish(record(java.util.logging.Level.FINE, "after the override"));
            handler.close();
        }

        assertEquals(1, sink.events.size());
        assertEquals("after the override", sink.events.getFirst().renderedMessage());
    }

    @Test
    void reloadedLevelsReachAnAlreadyInstalledHandler() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = runtime(sink, Level.INFO)) {
            QuarkusLogHandler handler = new QuarkusLogHandler(runtime);
            handler.publish(record(java.util.logging.Level.FINE, "before the reload"));
            assertEquals(0, sink.events.size());

            runtime.reload(plan(sink, Level.DEBUG));
            handler.publish(record(java.util.logging.Level.FINE, "after the reload"));
            handler.close();
        }

        assertEquals(1, sink.events.size());
        assertEquals("after the reload", sink.events.getFirst().renderedMessage());
    }

    @Test
    void anAdmittedRecordStillCannotReEnterTheHandlerThroughItsOwnSink() {
        ReentrantSink sink = new ReentrantSink();
        try (DefaultLogyardRuntime runtime = runtime(sink, Level.TRACE)) {
            QuarkusLogHandler handler = new QuarkusLogHandler(runtime);
            sink.handler = handler;

            handler.publish(record(java.util.logging.Level.INFO, "outer"));
            handler.close();
        }

        assertEquals(1, sink.events.size());
        assertEquals("outer", sink.events.getFirst().renderedMessage());
    }

    @Test
    void aRetiredHandlerAdmitsNothingAndLeavesTheSharedRuntimeUsable() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = runtime(sink, Level.TRACE)) {
            QuarkusLogHandler handler = new QuarkusLogHandler(runtime);
            handler.close();
            handler.close();

            handler.publish(record(java.util.logging.Level.INFO, "after retirement"));
            runtime.logger("quarkus.after.handler").info("runtime remains active");
        }

        assertEquals(1, sink.events.size());
        assertEquals("runtime remains active", sink.events.getFirst().renderedMessage());
    }

    private static LogRecord record(java.util.logging.Level level, String message) {
        ExtLogRecord record = new ExtLogRecord(
                level,
                message,
                ExtLogRecord.FormatStyle.NO_FORMAT,
                QuarkusLogHandlerAdmissionTest.class.getName());
        record.setLoggerName("quarkus.admission");
        return record;
    }

    private static DefaultLogyardRuntime runtime(EventSink sink, Level level) {
        return new DefaultLogyardRuntime(plan(sink, level));
    }

    private static RuntimePlan plan(EventSink sink, Level level) {
        return new RuntimePlan(
                RouteDefinition.root(level, List.of("capture"), List.of()),
                Map.of(),
                Map.of("capture", sink),
                Map.of(),
                Duration.ofSeconds(1));
    }

    private static final class RecordingSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();

        @Override
        public void accept(LogEvent event) {
            events.add(event);
        }
    }

    /** Sink that logs back through the handler that produced the event it is accepting. */
    private static final class ReentrantSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();
        private QuarkusLogHandler handler;

        @Override
        public void accept(LogEvent event) {
            events.add(event);
            handler.publish(record(java.util.logging.Level.INFO, "from the sink"));
        }
    }
}
