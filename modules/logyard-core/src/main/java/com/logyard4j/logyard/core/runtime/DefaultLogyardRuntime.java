package com.logyard4j.logyard.core.runtime;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.LogyardLogger;
import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.api.diagnostics.EffectiveRoute;
import com.logyard4j.logyard.api.diagnostics.RuntimeHealth;
import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.level.RuntimeLevelOverride;
import com.logyard4j.logyard.core.routing.CompiledRoute;
import com.logyard4j.logyard.core.runtime.management.RuntimeGeneration;
import com.logyard4j.logyard.core.runtime.management.RuntimeLifecycle;
import com.logyard4j.logyard.core.runtime.management.RuntimeLevelOverrideChange;
import com.logyard4j.logyard.core.runtime.management.RuntimeLevelOverrideManager;
import com.logyard4j.logyard.core.runtime.management.RuntimeManagementView;
import com.logyard4j.logyard.core.runtime.management.RuntimePlanReplacement;
import com.logyard4j.logyard.core.runtime.publication.RuntimePublication;
import com.logyard4j.logyard.core.runtime.retirement.RuntimeRetirements;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Thread-safe runtime with compiled routes and lease-protected atomic plan replacement. */
public final class DefaultLogyardRuntime implements LogyardRuntime {
    private volatile RuntimeGeneration state;
    private final RuntimePublication publication;
    private final RuntimeLevelOverrideManager levelOverrideManager;
    private final RuntimeRetirements retirements;
    private final RuntimeLifecycle lifecycle;
    private final RuntimePlanReplacement planReplacement;

    public DefaultLogyardRuntime(RuntimePlan plan) {
        state = RuntimePlanReplacement.initial(plan);
        retirements = new RuntimeRetirements();
        lifecycle = new RuntimeLifecycle(retirements);
        publication = new RuntimePublication(this, loggerName -> compileRoute(loggerName, state), lifecycle::closed);
        levelOverrideManager = new RuntimeLevelOverrideManager(publication);
        planReplacement = new RuntimePlanReplacement(publication, retirements);
    }

    @Override
    public LogyardLogger logger(Class<?> type) {
        return logger(Objects.requireNonNull(type, "type").getName());
    }

    @Override
    public LogyardLogger logger(String name) {
        return publication.logger(requireLoggerName(name));
    }

    @Override
    public EffectiveRoute explain(String loggerName) {
        String name = requireLoggerName(loggerName);
        CompiledRoute route = compileRoute(name, state);
        return new EffectiveRoute(
                name,
                route.level(),
                route.outputNames(),
                route.processorNames(),
                route.matchedRule(),
                route.enabledMask() != 0);
    }

    @Override
    public RuntimeHealth health() {
        return lifecycle.health(this::currentGeneration, publication::loggerCount);
    }

    public synchronized void reload(RuntimePlan nextPlan) {
        Objects.requireNonNull(nextPlan, "nextPlan");
        requireOpen();
        RuntimeGeneration previous = state;
        RuntimeGeneration next = planReplacement.prepare(nextPlan, previous);
        planReplacement.replace(previous, next, this::installState);
    }

    public synchronized void setLevelOverride(
            String loggerName,
            RuntimeLevelOverride override) {
        requireOpen();
        levelOverrideManager.set(state, loggerName, override).ifPresent(this::installState);
    }

    public synchronized void clearLevelOverride(String loggerName) {
        requireOpen();
        levelOverrideManager.clear(state, loggerName).ifPresent(this::installState);
    }

    public synchronized void clearAllLevelOverrides() {
        requireOpen();
        levelOverrideManager.clearAll(state).ifPresent(this::installState);
    }

    public Map<String, RuntimeLevelOverride> levelOverrides() {
        return state.levelOverrides().configuredLevels();
    }

    /** Returns exact logger thresholds from the currently active immutable plan. */
    public Map<String, Level> baseConfiguredLevels() {
        return state.plan().configuredLevels();
    }

    public Set<String> knownLoggerNames() {
        RuntimeGeneration snapshot = state;
        return RuntimeManagementView.knownLoggerNames(publication.loggerNames(), snapshot.plan(), snapshot.levelOverrides());
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
        RuntimeGeneration snapshot = state;
        return RuntimeManagementView.loggerLevelSnapshot(loggerName, snapshot.plan(), snapshot.levelOverrides());
    }

    public synchronized RuntimeManagementSnapshot managementSnapshot() {
        RuntimeGeneration snapshot = state;
        return RuntimeManagementView.snapshot(publication.loggerNames(), snapshot.plan(), snapshot.levelOverrides());
    }

    private void installState(RuntimeLevelOverrideChange change) {
        state = change.generation();
        publication.installRoutes(change.compiledRoutes());
    }

    private void installState(RuntimeGeneration generation, Map<String, CompiledRoute> compiledRoutes) {
        state = generation;
        publication.installRoutes(compiledRoutes);
    }

    @Override
    public void flush() {
        lifecycle.flush(this::currentGeneration);
    }

    @Override
    public void close() {
        RuntimeGeneration current;
        synchronized (this) {
            if (!lifecycle.beginClose()) {
                return;
            }
            current = state;
        }
        lifecycle.finishClose(current);
    }

    /** Completes only after final output retirement, even when {@link #close()} returns at its deadline. */
    public CompletionStage<Void> retirementCompletion() {
        return lifecycle.retirementCompletion();
    }

    private static CompiledRoute compileRoute(String loggerName, RuntimeGeneration state) {
        return RuntimePublication.compileRoute(loggerName, state.plan(), state.levelOverrides(), state.epoch());
    }

    private static String requireLoggerName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.length() > CaptureLimits.MAX_NAME_CHARS) {
            throw new IllegalArgumentException(
                    "logger name exceeds " + CaptureLimits.MAX_NAME_CHARS + " characters");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("logger name must not be blank");
        }
        return name;
    }

    private RuntimeGeneration currentGeneration() {
        return state;
    }

    public static DefaultLogyardRuntime consoleOnly(EventSink sink) {
        return new DefaultLogyardRuntime(RuntimePlans.consoleOnly(sink));
    }

    private void requireOpen() {
        lifecycle.requireOpen();
    }
}
