package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.routing.RouteDefinition;
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
