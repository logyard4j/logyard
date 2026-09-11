package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.context.LogContext;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.SystemAttributes;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.routing.RouteDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuilderAttributeSemanticsTest {
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
