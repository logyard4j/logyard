package com.logyard4j.core.runtime.publication;

import com.logyard4j.core.routing.CompiledRoute;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Acquires the current logger route, refreshing controls that still reference a retiring plan. */
final class RuntimeRouteLeases {
    private final BooleanSupplier runtimeClosed;
    private final RouteRefresher routeRefresher;

    RuntimeRouteLeases(BooleanSupplier runtimeClosed, RouteRefresher routeRefresher) {
        this.runtimeClosed = Objects.requireNonNull(runtimeClosed, "runtimeClosed");
        this.routeRefresher = Objects.requireNonNull(routeRefresher, "routeRefresher");
    }

    CompiledRouteLease acquire(LoggerControl control, String loggerName) {
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(loggerName, "loggerName");
        while (!runtimeClosed.getAsBoolean()) {
            CompiledRoute route = control.route;
            if (route.epoch().tryAcquire()) {
                return new CompiledRouteLease(route);
            }
            routeRefresher.refresh(control, loggerName);
        }
        return null;
    }

    @FunctionalInterface
    interface RouteRefresher {
        void refresh(LoggerControl control, String loggerName);
    }
}
