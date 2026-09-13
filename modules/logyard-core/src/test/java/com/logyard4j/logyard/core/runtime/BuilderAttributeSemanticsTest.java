package com.logyard4j.logyard.core.runtime;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.LogyardLogger;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.context.LogContext;
import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.api.event.SystemAttributes;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.routing.RouteDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuilderAttributeSemanticsTest {
    @Test
    void distinguishesDirectSupplierObjectsAndCapturesOnlyTheFinalLazyDeclaration() {
        List<LogEvent> events = new ArrayList<>();
        AtomicInteger evaluations = new AtomicInteger();
        StringBuilder mutable = new StringBuilder("declared");
        Thread caller = Thread.currentThread();
        Supplier<Object> direct = new Supplier<>() {
            @Override
            public Object get() {
                throw new AssertionError("direct supplier object evaluated");
            }

            @Override
            public String toString() {
                return "direct value";
            }
        };
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            var builder = runtime.logger("test.Declarations").atInfo()
                    .add("direct", direct)
                    .add("missing", null)
                    .addLazy("replaced", () -> { throw new AssertionError("replaced supplier evaluated"); })
                    .add("replaced", 42L)
                    .add("lazy", "old")
                    .addLazy("lazy", () -> {
                        assertSame(caller, Thread.currentThread());
                        evaluations.incrementAndGet();
                        return mutable;
                    });
            assertEquals(0, evaluations.get());
            mutable.replace(0, mutable.length(), "captured");
            builder.log("declarations");
            mutable.replace(0, mutable.length(), "later");
        }
        assertEquals(1, evaluations.get());
        assertEquals(1, events.size());
        Map<String, Object> attributes = events.getFirst().attributes().toMap();
        assertEquals("direct value", attributes.get("direct"));
        assertTrue(attributes.containsKey("missing"));
        assertNull(attributes.get("missing"));
        assertEquals(42L, attributes.get("replaced"));
        assertEquals("captured", attributes.get("lazy"));
    }

    @Test
    void addAllMergesPrebuiltSetsAndNullValuesStayEager() {
        RecordingSink sink = new RecordingSink();
        RuntimePlan plan = new RuntimePlan(
                RouteDefinition.root(Level.TRACE, List.of("capture"), List.of()),
                Map.of(),
                Map.of("capture", sink),
                Map.of());
        try (DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(plan)) {
            LogyardLogger logger = runtime.logger("test.Attributes");
            AttributeSet prebuilt = AttributeSet.builder(2)
                    .put("tenant", "north")
                    .put("order.id", 41L)
                    .build();
            logger.atInfo()
                    .add("order.id", 7L)
                    .addAll(prebuilt)
                    .add("missing", null)
                    .log("attributes");
        }

        assertEquals(1, sink.events.size());
        LogEvent event = sink.events.getFirst();
        Map<String, Object> attributes = event.attributes().toMap();
        assertEquals("north", attributes.get("tenant"));
        assertEquals(41L, attributes.get("order.id"), "addAll must replace an earlier key");
        assertTrue(attributes.containsKey("missing"), "a literal null value must be captured eagerly");
        assertNull(attributes.get("missing"));
    }

    @Test
    void aFullScopeStillAllowsExplicitReplacementWithoutEvaluatingDroppedSuppliers() {
        AttributeSet.Builder attributes = AttributeSet.builder(CaptureLimits.MAX_ATTRIBUTES);
        for (int index = 0; index < CaptureLimits.MAX_ATTRIBUTES; index++) {
            attributes.put("key." + index, "scoped");
        }
        List<LogEvent> events = new ArrayList<>();
        var scope = LogContext.push(attributes.build());
        try (scope; DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            runtime.logger("test.FullScope").atInfo()
                    .addLazy("new.key", () -> { throw new AssertionError("dropped supplier evaluated"); })
                    .addLazy("key.0", () -> "event")
                    .log("full scope");
        }
        assertEquals("event", events.getFirst().attributes().get("key.0"));
        assertEquals(true, events.getFirst().attributes().get(SystemAttributes.ATTRIBUTES_TRUNCATED));
    }

    @Test
    void addAllPreservesTruncationMarkersAndNormalizedKeyIdentity() {
        String longKey = "x".repeat(CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS + 50);
        AttributeSet prebuilt = AttributeSet.builder(2).put(longKey, "long").build();
        String capturedKey = prebuilt.keyAt(0);
        List<LogEvent> events = new ArrayList<>();
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            var logger = runtime.logger("test.Captured");
            logger.atInfo().addAll(prebuilt).add(capturedKey, "exact").log("collision");
            AttributeSet.Builder many = AttributeSet.builder(CaptureLimits.MAX_ATTRIBUTES + 1);
            for (int index = 0; index <= CaptureLimits.MAX_ATTRIBUTES; index++) many.put("key." + index, index);
            logger.with(many.build()).atInfo().log("truncated preset");
        }
        Map<String, Object> captured = events.getFirst().attributes().toMap();
        assertEquals("exact", captured.get(capturedKey));
        assertTrue(captured.containsValue("long"), "an exact key must not replace a normalized long key");
        assertEquals(true, captured.get(SystemAttributes.CAPTURE_TRUNCATED));
        assertEquals(true, events.get(1).attributes().get(SystemAttributes.ATTRIBUTES_TRUNCATED));
    }

    private static final class RecordingSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();

        @Override
        public void accept(LogEvent event) {
            events.add(event);
        }
    }
}
