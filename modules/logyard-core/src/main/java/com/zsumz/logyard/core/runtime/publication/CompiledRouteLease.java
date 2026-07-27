package com.zsumz.logyard.core.runtime.publication;

import com.zsumz.logyard.core.routing.CompiledRoute;

import java.util.Objects;

/** Closeable ownership token for one acquired compiled route. */
final class CompiledRouteLease implements AutoCloseable {
    private final CompiledRoute route;
    private LeasePhase phase = LeasePhase.HELD;

    CompiledRouteLease(CompiledRoute route) {
        this.route = Objects.requireNonNull(route, "route");
    }

    CompiledRoute route() {
        return route;
    }

    @Override
    public void close() {
        if (phase == LeasePhase.HELD) {
            phase = LeasePhase.RELEASED;
            route.epoch().release();
        }
    }

    private enum LeasePhase {
        HELD,
        RELEASED
    }
}
