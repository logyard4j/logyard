package com.zsumz.logyard.core.runtime.management;

import com.zsumz.logyard.core.level.RuntimeLevelOverrides;
import com.zsumz.logyard.core.routing.PlanEpoch;
import com.zsumz.logyard.core.runtime.RuntimePlan;

/** One immutable runtime plan generation and its lease-protected route state. */
public record RuntimeGeneration(
        RuntimePlan plan,
        RuntimeLevelOverrides levelOverrides,
        PlanEpoch epoch) {
}
