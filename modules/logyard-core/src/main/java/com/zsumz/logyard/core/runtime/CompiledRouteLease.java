package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.core.routing.CompiledRoute;

import java.util.Objects;

/** Closeable ownership token for one acquired compiled route. */
final class CompiledRouteLease implements AutoCloseable {
    private final CompiledRoute route;
    private boolean released;

    CompiledRouteLease(CompiledRoute route) {
        this.route = Objects.requireNonNull(route, "route");
    }

    CompiledRoute route() {
        return route;
    }

    @Override
    public void close() {
        if (!released) {
            released = true;
            route.epoch().release();
        }
    }
}
