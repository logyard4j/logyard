package com.logyard4j.core.runtime;

import com.logyard4j.api.Level;
import com.logyard4j.core.level.RuntimeLevelOverride;

/**
 * Allocation-light point view of one logger's base and runtime thresholds.
 *
 * @param baseConfiguredLevel exact base threshold, or {@code null}
 * @param runtimeOverride exact runtime override, or {@code null}
 * @param effectiveBaseLevel inherited base threshold
 * @param effectiveRuntimeOverride inherited runtime override, or {@code null}
 */
public record RuntimeLoggerLevelSnapshot(
        Level baseConfiguredLevel,
        RuntimeLevelOverride runtimeOverride,
        Level effectiveBaseLevel,
        RuntimeLevelOverride effectiveRuntimeOverride) {
}
