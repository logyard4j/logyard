package com.zsumz.logyard.core.runtime.publication;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.spi.processing.EventProcessor;
import com.zsumz.logyard.core.routing.CompiledRoute;
import com.zsumz.logyard.core.routing.PlanEpoch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class RuntimeRouteLeasesTest {
    @Test
    void refreshesAControlThatReferencesARetiringEpoch() {
        PlanEpoch retiringEpoch = new PlanEpoch();
        retiringEpoch.retire(() -> { }, Runnable::run);
        CompiledRoute activeRoute = route(new PlanEpoch());
        LoggerControl control = new LoggerControl(route(retiringEpoch));
        AtomicInteger refreshes = new AtomicInteger();
        RuntimeRouteLeases leases = new RuntimeRouteLeases(
                () -> false,
                (current, loggerName) -> {
                    assertEquals("test.Logger", loggerName);
                    refreshes.incrementAndGet();
                    current.update(activeRoute);
                });

        try (CompiledRouteLease lease = leases.acquire(control, "test.Logger")) {
            assertSame(activeRoute, lease.route());
        }

        assertEquals(1, refreshes.get());
    }

    private static CompiledRoute route(PlanEpoch epoch) {
        return new CompiledRoute(Level.INFO, ignored -> { }, new EventProcessor[0], List.of("test"), List.of(), "root", epoch);
    }
}
