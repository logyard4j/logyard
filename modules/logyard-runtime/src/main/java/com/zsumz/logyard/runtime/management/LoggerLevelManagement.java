package com.zsumz.logyard.runtime.management;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.core.level.RuntimeLevelOverride;
import com.zsumz.logyard.core.level.RuntimeLevelOverrides;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Framework-neutral control plane for temporary hierarchical logger-level overrides. */
public final class LoggerLevelManagement {
    public static final String ROOT_LOGGER_NAME = RuntimeLevelOverrides.ROOT_LOGGER_NAME;

    private final DefaultLogyardRuntime runtime;

    private LoggerLevelManagement(DefaultLogyardRuntime runtime) {
        this.runtime = runtime;
    }

    public static LoggerLevelManagement forRuntime(LogyardRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        if (runtime instanceof DefaultLogyardRuntime managed) {
            return new LoggerLevelManagement(managed);
        }
        throw new IllegalArgumentException("logger-level management requires the standard Logyard runtime");
    }

    public LoggerLevel getEffectiveLevel(String loggerName) {
        RuntimeLevelOverride override = runtime.effectiveLevelOverride(loggerName);
        return override == null
                ? LoggerLevel.from(runtime.explain(loggerName).level())
                : LoggerLevel.from(override);
    }

    public Map<String, LoggerLevel> listConfiguredLevels() {
        LinkedHashMap<String, LoggerLevel> levels = new LinkedHashMap<>();
        runtime.levelOverrides().forEach((logger, override) -> levels.put(logger, LoggerLevel.from(override)));
        return Collections.unmodifiableMap(levels);
    }

    public void setLevel(String loggerName, LoggerLevel level) {
        if (level == null) {
            clearLevel(loggerName);
            return;
        }
        runtime.setLevelOverride(loggerName, level.toOverride());
    }

    public void clearLevel(String loggerName) {
        runtime.clearLevelOverride(loggerName);
    }

    public void clearAllOverrides() {
        runtime.clearAllLevelOverrides();
    }
}
