package com.logyard4j.logyard.runtime.management;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.core.level.RuntimeLevelOverride;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.logyard.core.runtime.RuntimeManagementSnapshot;
import com.logyard4j.logyard.core.runtime.RuntimeLoggerLevelSnapshot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Converted management data derived from one immutable runtime generation. */
record LoggerManagementState(
        RuntimeManagementSnapshot runtime,
        Map<String, LoggerLevel> baseLevels,
        Map<String, LoggerLevel> overrides,
        Set<String> knownNames) {

    static LoggerManagementState capture(DefaultLogyardRuntime runtime) {
        RuntimeManagementSnapshot snapshot = runtime.managementSnapshot();
        Map<String, LoggerLevel> base = levels(snapshot.baseConfiguredLevels());
        Map<String, LoggerLevel> overrides = overrides(snapshot.levelOverrides());
        TreeSet<String> names = new TreeSet<>(snapshot.knownLoggerNames());
        names.add(LoggerLevelManagement.ROOT_LOGGER_NAME);
        return new LoggerManagementState(
                snapshot,
                base,
                overrides,
                Collections.unmodifiableSet(names));
    }

    LoggerLevelSnapshot logger(String name) {
        RuntimeLevelOverride effectiveOverride = runtime.effectiveLevelOverride(name);
        LoggerLevel effective = effectiveOverride == null
                ? LoggerLevel.from(runtime.effectiveBaseLevel(name))
                : LoggerLevel.from(effectiveOverride);
        return snapshot(baseLevels.get(name), overrides.get(name), effective, effectiveOverride);
    }

    static LoggerLevelSnapshot logger(RuntimeLoggerLevelSnapshot runtime) {
        LoggerLevel effective = runtime.effectiveRuntimeOverride() == null
                ? LoggerLevel.from(runtime.effectiveBaseLevel())
                : LoggerLevel.from(runtime.effectiveRuntimeOverride());
        return snapshot(from(runtime.baseConfiguredLevel()), from(runtime.runtimeOverride()), effective, runtime.effectiveRuntimeOverride());
    }

    private static LoggerLevelSnapshot snapshot(
            LoggerLevel baseConfigured,
            LoggerLevel runtimeOverride,
            LoggerLevel effective,
            RuntimeLevelOverride effectiveOverride) {
        LoggerLevel configured = runtimeOverride == null ? baseConfigured : runtimeOverride;
        LoggerLevelOrigin origin = effectiveOverride != null
                ? LoggerLevelOrigin.RUNTIME_OVERRIDE
                : baseConfigured == null ? LoggerLevelOrigin.INHERITED : LoggerLevelOrigin.BASE_CONFIGURATION;
        return new LoggerLevelSnapshot(configured, effective, baseConfigured, runtimeOverride, origin);
    }

    private static LoggerLevel from(Level level) {
        return level == null ? null : LoggerLevel.from(level);
    }

    private static LoggerLevel from(RuntimeLevelOverride override) {
        return override == null ? null : LoggerLevel.from(override);
    }

    private static Map<String, LoggerLevel> levels(Map<String, Level> source) {
        LinkedHashMap<String, LoggerLevel> levels = new LinkedHashMap<>();
        source.forEach((logger, level) -> levels.put(logger, LoggerLevel.from(level)));
        return Collections.unmodifiableMap(levels);
    }

    private static Map<String, LoggerLevel> overrides(Map<String, RuntimeLevelOverride> source) {
        LinkedHashMap<String, LoggerLevel> levels = new LinkedHashMap<>();
        source.forEach((logger, override) -> levels.put(logger, LoggerLevel.from(override)));
        return Collections.unmodifiableMap(levels);
    }
}
