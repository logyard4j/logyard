package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.context.ContextScope;
import com.zsumz.logyard.api.context.LogContext;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.context.ContextProvider;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.processing.ContextEnrichmentProcessor;
import com.zsumz.logyard.core.routing.RouteDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins how the configured {@code [context]} SPI stage and the native scoped context resolve collisions. */
final class ScopedContextEnrichmentPrecedenceTest {
    private final RecordingSink sink = new RecordingSink();

    @Test
    void configuredContextProvidersOverrideScopedContextAndEventAttributes() {
        try (DefaultLogyardRuntime runtime = runtime(AttributeSet.builder(2)
                .put("stage", "provider")
                .put("host", "node-1")
                .build())) {
            LogyardLogger logger = runtime.logger("test.Enrichment");
            ContextScope scope = LogContext.push("stage", "scoped");
            try (scope) {
                logger.atInfo().add("stage", "event").add("order.id", 7L).log("collision");
            }
        }

        LogEvent event = sink.events.getFirst();
        // Observed and pinned: the SPI context stage runs after capture and merges right-biased over the
        // whole event, so a provider key replaces both the native scoped value and a per-event attribute.
        // Native scoped context is therefore the weakest source only until enrichment runs, after which
        // configuration wins outright; keep provider allowlists disjoint from application scope keys.
        assertEquals("provider", event.attributes().get("stage"));
        assertEquals("node-1", event.attributes().get("host"));
        assertEquals(7L, event.attributes().get("order.id"), "non-colliding event attributes survive");
    }

    @Test
    void scopedContextSurvivesWhenProvidersDoNotCollide() {
        try (DefaultLogyardRuntime runtime = runtime(AttributeSet.of("host", "node-1"))) {
            LogyardLogger logger = runtime.logger("test.Enrichment");
            ContextScope scope = LogContext.push("tenant", "north");
            try (scope) {
                logger.info("no collision");
            }
        }

        LogEvent event = sink.events.getFirst();
        assertEquals("north", event.attributes().get("tenant"));
        assertEquals("node-1", event.attributes().get("host"));
    }

    private DefaultLogyardRuntime runtime(AttributeSet provided) {
        ContextEnrichmentProcessor processor = new ContextEnrichmentProcessor(
                List.of(new FixedContextProvider(provided)),
                List.of("stage", "host"));
        return new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.TRACE, List.of("capture"), List.of("context")),
                Map.of(),
                Map.of("capture", sink),
                Map.of("context", processor)));
    }

    private record FixedContextProvider(AttributeSet provided) implements ContextProvider {
        @Override
        public String name() {
            return "fixed";
        }

        @Override
        public AttributeSet capture(List<String> includedKeys) {
            return provided;
        }
    }

    private static final class RecordingSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();

        @Override
        public void accept(LogEvent event) {
            events.add(event);
        }
    }
}
