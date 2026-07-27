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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** Thread-safe runtime with compiled routes and lease-protected atomic plan replacement. */
public final class DefaultLogyardRuntime implements LogyardRuntime {
    private volatile RuntimeState state;
    private final RuntimeLoggerCatalog loggers = new RuntimeLoggerCatalog(
            this,
            loggerName -> compileRoute(loggerName, state),
            this::publish);
    private final RuntimeRetirements retirements = new RuntimeRetirements();
    private final CompletableFuture<Void> retirementCompletion = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final RuntimeRouteLeases routeLeases;
    private final EventPublicationPipeline publicationPipeline;

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
        return loggers.logger(name);
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
        loggers.forEachControl((name, control) -> compiled.put(name, compileRoute(name, next)));
        retirements.replacePlan(previous.plan(), previous.epoch(), next.plan(), () -> {
            state = next;
            loggers.updateRoutes(compiled);
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

    /** Returns exact logger thresholds from the currently active immutable plan. */
    public Map<String, Level> baseConfiguredLevels() {
        return state.plan().configuredLevels();
    }

    public Set<String> knownLoggerNames() {
        RuntimeState snapshot = state;
        return RuntimeManagementView.knownLoggerNames(loggers.controlNames(), snapshot.plan(), snapshot.levelOverrides());
    }

    public RuntimeLevelOverride effectiveLevelOverride(String loggerName) {
        return state.levelOverrides().resolve(loggerName);
    }

    /**
     * Resolves one logger's management thresholds from a single immutable runtime generation.
     *
     * @param loggerName normalized logger name
     * @return point-in-time logger threshold snapshot
     */
    public RuntimeLoggerLevelSnapshot loggerLevelSnapshot(String loggerName) {
        Objects.requireNonNull(loggerName, "loggerName");
        RuntimeState snapshot = state;
        return RuntimeManagementView.loggerLevelSnapshot(loggerName, snapshot.plan(), snapshot.levelOverrides());
    }

    public synchronized RuntimeManagementSnapshot managementSnapshot() {
        RuntimeState snapshot = state;
        return RuntimeManagementView.snapshot(loggers.controlNames(), snapshot.plan(), snapshot.levelOverrides());
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
        loggers.forEachControl((name, control) -> {
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
        loggers.updateRoutes(compiled);
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
    public void close() {
        RuntimeState current;
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            current = state;
        }
        try {
            retirements.finishPlan(current.plan(), current.epoch()).whenComplete((ignored, failure) -> {
                if (failure == null) {
                    retirementCompletion.complete(null);
                } else {
                    retirementCompletion.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException | Error failure) {
            retirementCompletion.completeExceptionally(failure);
            throw failure;
        }
        retirements.await(current.plan().shutdownTimeout());
    }

    /** Completes only after final output retirement, even when {@link #close()} returns at its deadline. */
    public CompletionStage<Void> retirementCompletion() {
        return retirementCompletion;
    }

    private static CompiledRoute compileRoute(String loggerName, RuntimeState state) {
        return RuntimeRouteCompiler.compile(loggerName, state.plan(), state.levelOverrides(), state.epoch());
    }

    private RuntimeHealth stoppedHealth() {
        return RuntimeHealthReporter.stopped(loggers.size());
    }

    public static DefaultLogyardRuntime consoleOnly(EventSink sink) {
        return new DefaultLogyardRuntime(RuntimePlans.consoleOnly(sink));
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
