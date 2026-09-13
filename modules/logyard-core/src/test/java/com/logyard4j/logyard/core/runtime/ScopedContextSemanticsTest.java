package com.logyard4j.logyard.core.runtime;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.LogyardLogger;
import com.logyard4j.logyard.api.context.ContextScope;
import com.logyard4j.logyard.api.context.LogContext;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.routing.RouteDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that the native scoped context reaches every ingress path with the documented precedence. */
final class ScopedContextSemanticsTest {
    private final RecordingSink sink = new RecordingSink();

    @Test
    void eventsInsideAScopeCarryTheContextAndEventsAfterItDoNot() {
        try (DefaultLogyardRuntime runtime = runtime()) {
            LogyardLogger logger = runtime.logger("test.Scoped");
            ContextScope scope = LogContext.push(AttributeSet.builder(2)
                    .put("tenant", "north")
                    .put("request.id", "req-1")
                    .build());
            try (scope) {
                logger.atInfo().add("order.id", 7L).log("inside");
            }
            logger.atInfo().log("outside");
        }

        LogEvent inside = sink.events.get(0);
        assertEquals("north", inside.attributes().get("tenant"));
        assertEquals("req-1", inside.attributes().get("request.id"));
        assertEquals(7L, inside.attributes().get("order.id"));

        LogEvent outside = sink.events.get(1);
        assertNull(outside.attributes().get("tenant"));
        assertSame(AttributeSet.EMPTY, outside.attributes(), "an unscoped event must not allocate attributes");
    }

    @Test
    void perEventAttributesReplaceScopedContextOnTheSameKey() {
        try (DefaultLogyardRuntime runtime = runtime()) {
            LogyardLogger logger = runtime.logger("test.Override");
            ContextScope scope = LogContext.push("stage", "scoped");
            try (scope) {
                logger.atInfo().add("stage", "event").log("builder");
                logger.log(Level.INFO, null, "ingress", null, AttributeSet.of("stage", "ingress"), null);
            }
        }

        assertEquals("event", sink.events.get(0).attributes().get("stage"));
        assertEquals("ingress", sink.events.get(1).attributes().get("stage"));
    }

    @Test
    void curriedPresetsBeatScopedContextAndLoseToEventAttributes() {
        try (DefaultLogyardRuntime runtime = runtime()) {
            LogyardLogger logger = runtime.logger("test.Curried")
                    .with(AttributeSet.builder(2).put("stage", "preset").put("component", "checkout").build());
            ContextScope scope = LogContext.push(AttributeSet.builder(2)
                    .put("stage", "scoped")
                    .put("tenant", "north")
                    .build());
            try (scope) {
                logger.atInfo().log("preset wins");
                logger.atInfo().add("stage", "event").log("event wins");
            }
        }

        LogEvent presetWins = sink.events.get(0);
        assertEquals("preset", presetWins.attributes().get("stage"));
        assertEquals("checkout", presetWins.attributes().get("component"));
        assertEquals("north", presetWins.attributes().get("tenant"), "presets do not hide unrelated scoped keys");
        assertEquals("event", sink.events.get(1).attributes().get("stage"));
    }

    @Test
    void convenienceMethodsCarryContextAndPresets() {
        try (DefaultLogyardRuntime runtime = runtime()) {
            LogyardLogger logger = runtime.logger("test.Convenience");
            ContextScope scope = LogContext.push("tenant", "north");
            try (scope) {
                logger.info("plain {}", "message");
                logger.with("component", "checkout").warn("curried");
                logger.with("tenant", "override").error("preset over scope");
            }
        }

        assertEquals("north", sink.events.get(0).attributes().get("tenant"));
        assertEquals("plain {}", sink.events.get(0).messageTemplate());
        assertEquals("north", sink.events.get(1).attributes().get("tenant"));
        assertEquals("checkout", sink.events.get(1).attributes().get("component"));
        assertEquals("override", sink.events.get(2).attributes().get("tenant"));
    }

    @Test
    void disabledEventsNeverConsultTheContext() {
        try (DefaultLogyardRuntime runtime = runtime(Level.INFO)) {
            LogyardLogger logger = runtime.logger("test.Disabled");
            ContextScope scope = LogContext.push("tenant", "north");
            try (scope) {
                logger.atDebug().add("order.id", 7L).log("suppressed");
                logger.debug("suppressed");
            }
        }
        assertTrue(sink.events.isEmpty());
    }

    @Test
    void virtualThreadsEachSeeTheirOwnContext() throws Exception {
        try (DefaultLogyardRuntime runtime = runtime()) {
            LogyardLogger logger = runtime.logger("test.Virtual");
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> pending = new ArrayList<>();
                for (int worker = 0; worker < 8; worker++) {
                    long identifier = worker;
                    pending.add(executor.submit(() -> {
                        ContextScope scope = LogContext.push("worker", identifier);
                        try (scope) {
                            logger.atInfo().log("virtual");
                        }
                        return LogContext.current().isEmpty();
                    }));
                }
                for (Future<?> future : pending) {
                    assertEquals(true, future.get(), "each virtual thread unbinds its own context");
                }
            }
        }

        Set<Object> workers = new HashSet<>();
        for (LogEvent event : sink.events) {
            workers.add(event.attributes().get("worker"));
        }
        assertEquals(8, sink.events.size());
        assertEquals(8, workers.size(), "no virtual thread may observe another thread's context");
    }

    private DefaultLogyardRuntime runtime() {
        return runtime(Level.TRACE);
    }

    private DefaultLogyardRuntime runtime(Level level) {
        return new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(level, List.of("capture"), List.of()),
                Map.of(),
                Map.of("capture", sink),
                Map.of()));
    }

    private static final class RecordingSink implements EventSink {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void accept(LogEvent event) {
            events.add(event);
        }
    }
}
