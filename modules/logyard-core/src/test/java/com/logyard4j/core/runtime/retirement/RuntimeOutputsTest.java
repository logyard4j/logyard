package com.logyard4j.core.runtime.retirement;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.routing.RouteDefinition;
import com.logyard4j.core.runtime.RuntimePlan;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RuntimeOutputsTest {
    @Test
    void closesEachObsoleteOutputIdentityExactlyOnce() {
        CountingSink reused = new CountingSink();
        CountingSink obsolete = new CountingSink();
        Map<String, EventSink> previousOutputs = new LinkedHashMap<>();
        previousOutputs.put("primary", reused);
        previousOutputs.put("alias", reused);
        previousOutputs.put("obsolete", obsolete);

        RuntimeOutputs.closeNotReused(plan(previousOutputs), plan(Map.of("renamed", reused)));

        assertEquals(0, reused.closes.get());
        assertEquals(1, obsolete.closes.get());
    }

    private static RuntimePlan plan(Map<String, EventSink> outputs) {
        return new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of(outputs.keySet().iterator().next()), List.of()),
                Map.of(),
                outputs,
                Map.of());
    }

    private static final class CountingSink implements EventSink {
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public void accept(LogEvent event) {
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
