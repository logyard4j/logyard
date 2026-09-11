package com.logyard4j.core.runtime.publication;

import com.logyard4j.api.Level;
import com.logyard4j.api.spi.processing.EventProcessor;
import com.logyard4j.core.routing.CompiledRoute;
import com.logyard4j.core.routing.PlanEpoch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CompiledRouteLeaseTest {
    @Test
    void releasesAnAcquiredEpochExactlyOnce() {
        PlanEpoch epoch = new PlanEpoch();
        AtomicInteger cleanups = new AtomicInteger();
        assertTrue(epoch.tryAcquire());
        CompiledRouteLease lease = new CompiledRouteLease(route(epoch));

        epoch.retire(cleanups::incrementAndGet, Runnable::run);

        assertFalse(epoch.tryAcquire());
        assertEquals(0, cleanups.get());
        lease.close();
        lease.close();
        assertEquals(1, cleanups.get());
    }

    private static CompiledRoute route(PlanEpoch epoch) {
        return new CompiledRoute(Level.INFO, ignored -> { }, new EventProcessor[0], List.of("test"), List.of(), "root", epoch);
    }
}
