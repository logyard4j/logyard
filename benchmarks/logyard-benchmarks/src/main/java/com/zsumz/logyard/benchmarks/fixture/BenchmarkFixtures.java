package com.zsumz.logyard.benchmarks.fixture;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.routing.RouteDefinition;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.core.runtime.RuntimePlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared immutable fixtures for comparable JMH scenarios. */
public final class BenchmarkFixtures {
    public static final EventSink DISCARDING_SINK = event -> { };

    private BenchmarkFixtures() {
    }

    public static DefaultLogyardRuntime runtime(Level level, int outputCount) {
        return runtime(level, outputCount, DISCARDING_SINK);
    }

    public static ObservedRuntime observedRuntime(Level level, int outputCount) {
        ObservingSink sink = new ObservingSink();
        return new ObservedRuntime(runtime(level, outputCount, sink), sink);
    }

    private static DefaultLogyardRuntime runtime(Level level, int outputCount, EventSink sink) {
        Map<String, EventSink> outputs = new LinkedHashMap<>();
        for (int index = 0; index < outputCount; index++) {
            outputs.put("output-" + index, sink);
        }
        return new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(level, List.copyOf(outputs.keySet()), List.of()),
                Map.of(),
                outputs,
                Map.of()));
    }

    public static LogEvent event(AttributeSet attributes) {
        return new LogEvent(
                1_700_000_000_000L,
                1_700_000_000_000_000_000L,
                Level.INFO,
                "com.example.orders.OrderService",
                "order.accepted",
                "accepted order {} for {}",
                new Object[] {42L, "customer-7"},
                attributes,
                null,
                7L,
                "benchmark-worker");
    }

    public record ObservedRuntime(DefaultLogyardRuntime runtime, ObservingSink sink) {
    }

    public static final class ObservingSink implements EventSink {
        private volatile LogEvent last;

        @Override
        public void accept(LogEvent event) {
            last = event;
        }

        public LogEvent last() {
            return last;
        }
    }
}
