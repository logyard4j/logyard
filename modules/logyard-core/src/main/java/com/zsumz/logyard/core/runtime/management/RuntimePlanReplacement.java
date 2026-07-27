package com.zsumz.logyard.core.runtime.management;

import com.zsumz.logyard.core.level.RuntimeLevelOverrides;
import com.zsumz.logyard.core.routing.CompiledRoute;
import com.zsumz.logyard.core.routing.PlanEpoch;
import com.zsumz.logyard.core.runtime.RuntimePlan;
import com.zsumz.logyard.core.runtime.publication.RuntimePublication;
import com.zsumz.logyard.core.runtime.retirement.RuntimeRetirements;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Prepares and atomically publishes a replacement generation before retiring its predecessor. */
public final class RuntimePlanReplacement {
    private final RuntimePublication publication;
    private final RuntimeRetirements retirements;

    public RuntimePlanReplacement(RuntimePublication publication, RuntimeRetirements retirements) {
        this.publication = Objects.requireNonNull(publication, "publication");
        this.retirements = Objects.requireNonNull(retirements, "retirements");
    }

    public RuntimeGeneration prepare(RuntimePlan nextPlan, RuntimeGeneration previous) {
        Objects.requireNonNull(nextPlan, "nextPlan");
        Objects.requireNonNull(previous, "previous");
        return new RuntimeGeneration(nextPlan, previous.levelOverrides(), new PlanEpoch());
    }

    public void replace(
            RuntimeGeneration previous,
            RuntimeGeneration next,
            BiConsumer<RuntimeGeneration, Map<String, CompiledRoute>> activation) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(activation, "activation");
        Map<String, CompiledRoute> compiled = publication.compileRoutes(
                name -> RuntimePublication.compileRoute(name, next.plan(), next.levelOverrides(), next.epoch()),
                ignored -> true);
        retirements.replacePlan(
                previous.plan(), previous.epoch(), next.plan(), () -> activation.accept(next, compiled));
    }

    public static RuntimeGeneration initial(RuntimePlan plan) {
        return new RuntimeGeneration(
                Objects.requireNonNull(plan, "plan"), RuntimeLevelOverrides.empty(), new PlanEpoch());
    }
}
