package com.logyard4j.logyard.core.runtime.management;

import com.logyard4j.logyard.core.level.RuntimeLevelOverrides;
import com.logyard4j.logyard.core.routing.PlanEpoch;
import com.logyard4j.logyard.core.runtime.RuntimePlan;

/** One immutable runtime plan generation and its lease-protected route state. */
public record RuntimeGeneration(
        RuntimePlan plan,
        RuntimeLevelOverrides levelOverrides,
        PlanEpoch epoch) {
}
