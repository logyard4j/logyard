package com.logyard4j.core.runtime.publication;

import com.logyard4j.api.LogyardLogger;
import com.logyard4j.core.level.RuntimeLevelOverrides;
import com.logyard4j.core.routing.CompiledRoute;
import com.logyard4j.core.routing.PlanEpoch;
import com.logyard4j.core.runtime.RuntimePlan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/** Owns stable loggers, route leases, and the complete event-publication pipeline for one runtime. */
public final class RuntimePublication {
    private final Object stateLock;
    private final Function<String, CompiledRoute> routeCompiler;
    private final RuntimeLoggerCatalog loggers;
    private final RuntimeRouteLeases routeLeases;
    private final EventPublicationPipeline pipeline;

    public RuntimePublication(
            Object stateLock,
            Function<String, CompiledRoute> routeCompiler,
            BooleanSupplier runtimeClosed) {
        this.stateLock = Objects.requireNonNull(stateLock, "stateLock");
        this.routeCompiler = Objects.requireNonNull(routeCompiler, "routeCompiler");
        loggers = new RuntimeLoggerCatalog(stateLock, this::compileRoute, this::publish);
        routeLeases = new RuntimeRouteLeases(Objects.requireNonNull(runtimeClosed, "runtimeClosed"), this::refreshRoute);
        pipeline = new EventPublicationPipeline(new EmergencyPublicationFailureHandler());
    }

    public LogyardLogger logger(String name) {
        return loggers.logger(name);
    }

    public int loggerCount() {
        return loggers.size();
    }

    public Set<String> loggerNames() {
        return loggers.controlNames();
    }

    public Map<String, CompiledRoute> compileRoutes(
            Function<String, CompiledRoute> compiler,
            Predicate<String> affected) {
        Objects.requireNonNull(compiler, "compiler");
        Objects.requireNonNull(affected, "affected");
        Map<String, CompiledRoute> routes = new LinkedHashMap<>();
        loggers.forEachControl((name, ignored) -> {
            if (affected.test(name)) {
                routes.put(name, compiler.apply(name));
            }
        });
        return routes;
    }

    public void installRoutes(Map<String, CompiledRoute> routes) {
        loggers.updateRoutes(Objects.requireNonNull(routes, "routes"));
    }

    /** Compiles one immutable route for the runtime state supplied by its owner. */
    public static CompiledRoute compileRoute(
            String loggerName,
            RuntimePlan plan,
            RuntimeLevelOverrides overrides,
            PlanEpoch epoch) {
        return RuntimeRouteCompiler.compile(loggerName, plan, overrides, epoch);
    }

    private CompiledRoute compileRoute(String loggerName) {
        return routeCompiler.apply(loggerName);
    }

    private void refreshRoute(LoggerControl control, String loggerName) {
        synchronized (stateLock) {
            control.update(routeCompiler.apply(loggerName));
        }
    }

    private void publish(LoggerControl control, EventDraft draft) {
        CompiledRouteLease lease = routeLeases.acquire(control, draft.loggerName());
        if (lease == null) {
            return;
        }
        try (lease) {
            pipeline.publish(lease.route(), draft);
        }
    }

    @FunctionalInterface
    interface EventPublisher {
        void publish(LoggerControl control, EventDraft draft);
    }
}
