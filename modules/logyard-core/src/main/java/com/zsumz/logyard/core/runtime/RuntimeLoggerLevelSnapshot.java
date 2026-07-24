package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.core.level.RuntimeLevelOverride;

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
