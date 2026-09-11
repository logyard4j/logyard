package com.logyard4j.slf4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.logyard4j.api.Level;
import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.api.context.ContextScope;
import com.logyard4j.api.context.LogContext;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.routing.RouteDefinition;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.core.runtime.RuntimePlan;
import com.logyard4j.slf4j.internal.context.ContextSnapshotPolicy;
import com.logyard4j.slf4j.internal.context.LogyardMdcAdapter;
import com.logyard4j.slf4j.internal.event.Slf4jEventMapper;
import com.logyard4j.slf4j.internal.factory.LogyardLoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/** Verifies that the native scoped context reaches events published through the SLF4J provider. */
final class Slf4jScopedContextTest {
    private final RecordingSink sink = new RecordingSink();

    @Test
    void scopedContextReachesConventionalAndFluentSlf4jEvents() {
        try (LogyardRuntime runtime = runtime()) {
            Logger logger = loggerFactory(runtime, new LogyardMdcAdapter()).getLogger("test.Scoped");
            ContextScope scope = LogContext.push(AttributeSet.builder(2)
                    .put("tenant", "north")
                    .put("request.id", "req-1")
                    .build());
            try (scope) {
                logger.info("conventional {}", 7);
                logger.atWarn().addKeyValue("order.id", 7L).log("fluent");
            }
            logger.info("after the scope");
        }

        LogEvent conventional = sink.events.get(0);
        assertEquals("north", conventional.attributes().get("tenant"));
        assertEquals("req-1", conventional.attributes().get("request.id"));

        LogEvent fluent = sink.events.get(1);
        assertEquals("north", fluent.attributes().get("tenant"));
        assertEquals(7L, fluent.attributes().get("order.id"));

        assertNull(sink.events.get(2).attributes().get("tenant"), "a closed scope must not follow the logger");
    }

    @Test
    void mdcValuesReplaceScopedContextOnACollidingKey() {
        try (LogyardRuntime runtime = runtime()) {
            LogyardMdcAdapter mdc = new LogyardMdcAdapter();
            mdc.put("request.id", "mdc-request");
            Logger logger = loggerFactory(runtime, mdc).getLogger("test.Collision");
            ContextScope scope = LogContext.push(AttributeSet.builder(2)
                    .put("request.id", "scoped-request")
                    .put("tenant", "north")
                    .build());
            try (scope) {
                logger.info("collision");
            }
        }

        LogEvent event = sink.events.getFirst();
        // Adapter-supplied attributes are event attributes, so MDC replaces a colliding scoped key.
        assertEquals("mdc-request", event.attributes().get("request.id"));
        assertEquals("north", event.attributes().get("tenant"));
    }

    private static LogyardLoggerFactory loggerFactory(LogyardRuntime runtime, LogyardMdcAdapter mdc) {
        return new LogyardLoggerFactory(
                runtime,
                new Slf4jEventMapper(mdc, new ContextSnapshotPolicy(List.of("request.id"))));
    }

    private LogyardRuntime runtime() {
        return new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("capture"), List.of()),
                Map.of(),
                Map.of("capture", sink),
                Map.of()));
    }

    private static final class RecordingSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();

        @Override
        public void accept(LogEvent event) {
            events.add(event);
        }
    }
}
