package com.logyard4j.core.runtime.management;

import com.logyard4j.core.level.RuntimeLevelOverride;
import com.logyard4j.core.level.RuntimeLevelOverrides;
import com.logyard4j.core.routing.CompiledRoute;
import com.logyard4j.core.runtime.publication.RuntimePublication;

import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** Prepares immutable level-override transitions without owning runtime state publication. */
public final class RuntimeLevelOverrideManager {
    private final RuntimePublication publication;

    public RuntimeLevelOverrideManager(RuntimePublication publication) {
        this.publication = publication;
    }

    /** Prepares a transition that applies one override to matching existing logger routes. */
    public Optional<RuntimeLevelOverrideChange> set(
            RuntimeGeneration current,
            String loggerName,
            RuntimeLevelOverride override) {
        RuntimeLevelOverrides nextOverrides = current.levelOverrides().withLevel(loggerName, override);
        return change(current, nextOverrides, name -> nextOverrides.affects(loggerName, name));
    }

    /** Prepares a transition that removes one override from matching existing logger routes. */
    public Optional<RuntimeLevelOverrideChange> clear(RuntimeGeneration current, String loggerName) {
        RuntimeLevelOverrides nextOverrides = current.levelOverrides().withoutLevel(loggerName);
        return change(current, nextOverrides, name -> nextOverrides.affects(loggerName, name));
    }

    /** Prepares a transition that removes every override from every existing logger route. */
    public Optional<RuntimeLevelOverrideChange> clearAll(RuntimeGeneration current) {
        return change(current, current.levelOverrides().clear(), ignored -> true);
    }

    private Optional<RuntimeLevelOverrideChange> change(
            RuntimeGeneration current,
            RuntimeLevelOverrides nextOverrides,
            Predicate<String> affected) {
        if (nextOverrides == current.levelOverrides()) {
            return Optional.empty();
        }
        RuntimeGeneration next = new RuntimeGeneration(current.plan(), nextOverrides, current.epoch());
        Map<String, CompiledRoute> compiled = publication.compileRoutes(
                name -> RuntimePublication.compileRoute(name, next.plan(), next.levelOverrides(), next.epoch()),
                affected);
        return Optional.of(new RuntimeLevelOverrideChange(next, compiled));
    }
}
