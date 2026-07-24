package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.diagnostics.EffectiveRoute;
import com.zsumz.logyard.api.diagnostics.RuntimeHealth;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.level.RuntimeLevelOverride;
import com.zsumz.logyard.core.level.RuntimeLevelOverrides;
import com.zsumz.logyard.core.routing.CompiledRoute;
import com.zsumz.logyard.core.routing.PlanEpoch;
import com.zsumz.logyard.core.routing.RouteDefinition;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** Thread-safe runtime with compiled routes and lease-protected atomic plan replacement. */
public final class DefaultLogyardRuntime implements LogyardRuntime {
    private final ConcurrentHashMap<String, DefaultLogyardLogger> loggers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LoggerControl> controls = new ConcurrentHashMap<>();
    private final RuntimeRetirements retirements = new RuntimeRetirements();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final RuntimeRouteLeases routeLeases;
    private final EventPublicationPipeline publicationPipeline;
    private volatile RuntimeState state;

    public DefaultLogyardRuntime(RuntimePlan plan) {
        state = new RuntimeState(
                Objects.requireNonNull(plan, "plan"),
                RuntimeLevelOverrides.empty(),
                new PlanEpoch());
        routeLeases = new RuntimeRouteLeases(closed::get, this::refreshRoute);
        publicationPipeline = new EventPublicationPipeline(new EmergencyPublicationFailureHandler());
    }

    @Override
    public LogyardLogger logger(Class<?> type) {
        return logger(Objects.requireNonNull(type, "type").getName());
    }

    @Override
    public LogyardLogger logger(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("logger name must not be blank");
        }
        if (name.length() > CaptureLimits.MAX_NAME_CHARS) {
            throw new IllegalArgumentException(
                    "logger name exceeds " + CaptureLimits.MAX_NAME_CHARS + " characters");
        }
        return loggers.computeIfAbsent(name, key -> {
            synchronized (this) {
                LoggerControl control = new LoggerControl(compileRoute(key, state));
                controls.put(key, control);
                return new DefaultLogyardLogger(key, this, control);
            }
        });
    }

    @Override
    public EffectiveRoute explain(String loggerName) {
        Objects.requireNonNull(loggerName, "loggerName");
        CompiledRoute route = compileRoute(loggerName, state);
        return new EffectiveRoute(
                loggerName,
                route.level(),
                route.outputNames(),
                route.processorNames(),
                route.matchedRule());
    }

    @Override
    public RuntimeHealth health() {
        if (closed.get()) {
            return stoppedHealth();
        }

        RuntimeState snapshot;
        while (true) {
            snapshot = state;
            if (snapshot.epoch().tryAcquire()) {
                break;
            }
            if (closed.get()) {
                return stoppedHealth();
            }
        }
        try {
            return RuntimeHealthReporter.running(loggers.size(), retirements.pendingCount(), snapshot.plan(), snapshot.epoch());
        } finally {
            snapshot.epoch().release();
        }
    }

    public synchronized void reload(RuntimePlan nextPlan) {
        Objects.requireNonNull(nextPlan, "nextPlan");
        requireOpen();
        RuntimeState previous = state;
        RuntimeState next = new RuntimeState(nextPlan, previous.levelOverrides(), new PlanEpoch());
        Map<String, CompiledRoute> compiled = new LinkedHashMap<>();
        controls.forEach((name, control) -> compiled.put(name, compileRoute(name, next)));
        retirements.replacePlan(previous.plan(), previous.epoch(), next.plan(), () -> {
            state = next;
            compiled.forEach((name, route) -> controls.get(name).update(route));
        });
    }

    public synchronized void setLevelOverride(
            String loggerName,
            RuntimeLevelOverride override) {
        requireOpen();
        RuntimeState current = state;
        RuntimeLevelOverrides nextOverrides = current.levelOverrides().withLevel(loggerName, override);
        publishLevelOverrides(current, nextOverrides, loggerName);
    }

    public synchronized void clearLevelOverride(String loggerName) {
        requireOpen();
        RuntimeState current = state;
        RuntimeLevelOverrides nextOverrides = current.levelOverrides().withoutLevel(loggerName);
        publishLevelOverrides(current, nextOverrides, loggerName);
    }

    public synchronized void clearAllLevelOverrides() {
        requireOpen();
        RuntimeState current = state;
        RuntimeLevelOverrides nextOverrides = current.levelOverrides().clear();
        if (nextOverrides == current.levelOverrides()) {
            return;
        }
        RuntimeState next = new RuntimeState(current.plan(), nextOverrides, current.epoch());
        Map<String, CompiledRoute> compiled = compileExistingControls(next, ignored -> true);
        publishState(next, compiled);
    }

    public Map<String, RuntimeLevelOverride> levelOverrides() {
        return state.levelOverrides().configuredLevels();
    }

    public Set<String> knownLoggerNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>(controls.keySet());
        names.addAll(state.levelOverrides().configuredLevels().keySet());
        return Set.copyOf(names);
    }

    public RuntimeLevelOverride effectiveLevelOverride(String loggerName) {
        return state.levelOverrides().resolve(loggerName);
    }

    void publish(LoggerControl control, EventDraft draft) {
        CompiledRouteLease lease = routeLeases.acquire(control, draft.loggerName());
        if (lease == null) {
            return;
        }
        try (lease) {
            publicationPipeline.publish(lease.route(), draft);
        }
    }

    private synchronized void refreshRoute(LoggerControl control, String loggerName) {
        control.update(compileRoute(loggerName, state));
    }

    private void publishLevelOverrides(
            RuntimeState current,
            RuntimeLevelOverrides nextOverrides,
            String changedLogger) {
        if (nextOverrides == current.levelOverrides()) {
            return;
        }
        RuntimeState next = new RuntimeState(current.plan(), nextOverrides, current.epoch());
        Map<String, CompiledRoute> compiled = compileExistingControls(
                next,
                loggerName -> nextOverrides.affects(changedLogger, loggerName));
        publishState(next, compiled);
    }

    private Map<String, CompiledRoute> compileExistingControls(
            RuntimeState next,
            Predicate<String> affected) {
        Map<String, CompiledRoute> compiled = new LinkedHashMap<>();
        controls.forEach((name, control) -> {
            if (affected.test(name)) {
                compiled.put(name, compileRoute(name, next));
            }
        });
        return compiled;
    }

    private void publishState(
            RuntimeState next,
            Map<String, CompiledRoute> compiled) {
        state = next;
        compiled.forEach((name, route) -> controls.get(name).update(route));
    }

    @Override
    public void flush() {
        RuntimeState snapshot;
        while (true) {
            if (closed.get()) {
                return;
            }
            snapshot = state;
            if (snapshot.epoch().tryAcquire()) {
                break;
            }
        }
        try {
            RuntimeOutputs.flush(snapshot.plan());
        } finally {
            snapshot.epoch().release();
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeState current = state;
        retirements.finishPlan(current.plan(), current.epoch());
        retirements.await(current.plan().shutdownTimeout());
    }

    private static CompiledRoute compileRoute(String loggerName, RuntimeState state) {
        return RuntimeRouteCompiler.compile(loggerName, state.plan(), state.levelOverrides(), state.epoch());
    }

    private RuntimeHealth stoppedHealth() {
        return RuntimeHealthReporter.stopped(loggers.size());
    }

    public static DefaultLogyardRuntime consoleOnly(EventSink sink) {
        Map<String, EventSink> outputs = new LinkedHashMap<>();
        outputs.put("console", sink);
        return new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("console"), List.of()),
                Map.of(),
                outputs,
                Map.of()));
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("runtime is closed");
        }
    }

    private record RuntimeState(
            RuntimePlan plan,
            RuntimeLevelOverrides levelOverrides,
            PlanEpoch epoch) {
    }
}
