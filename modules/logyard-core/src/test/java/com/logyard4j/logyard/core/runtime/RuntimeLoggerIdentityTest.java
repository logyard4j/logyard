package com.logyard4j.logyard.core.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.LogyardLogger;
import com.logyard4j.logyard.core.routing.RouteDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class RuntimeLoggerIdentityTest {
    @Test
    void concurrentFirstLookupAndCachedLookupShareAnIdentityThatFollowsReload() throws Exception {
        try (DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(plan(Level.INFO));
                var workers = Executors.newFixedThreadPool(16)) {
            CyclicBarrier start = new CyclicBarrier(16);
            ArrayList<Future<LogyardLogger>> results = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                results.add(workers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return runtime.logger("shared.Logger");
                }));
            }
            LogyardLogger first = results.getFirst().get(5, TimeUnit.SECONDS);
            for (Future<LogyardLogger> result : results) {
                assertSame(first, result.get(5, TimeUnit.SECONDS));
            }
            assertTrue(first.isEnabled(Level.INFO));
            runtime.reload(plan(Level.WARN));
            assertSame(first, runtime.logger("shared.Logger"));
            assertFalse(first.isEnabled(Level.INFO));
            assertTrue(first.isEnabled(Level.WARN));
        }
    }

    private static RuntimePlan plan(Level level) {
        return new RuntimePlan(RouteDefinition.root(level, List.of("output"), List.of()),
                Map.of(), Map.of("output", event -> { }), Map.of());
    }
}
