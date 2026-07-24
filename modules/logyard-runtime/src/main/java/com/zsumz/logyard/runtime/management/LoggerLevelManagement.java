package com.zsumz.logyard.runtime.management;

import com.zsumz.logyard.api.LogyardRuntime;
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
        String normalized = normalize(loggerName);
        return state().logger(normalized).effectiveLevel();
    }

    public Map<String, LoggerLevel> listConfiguredLevels() {
        return state().overrides();
    }

    /** Returns exact thresholds from the active base configuration, including {@code ROOT}. */
    public Map<String, LoggerLevel> listBaseConfiguredLevels() {
        return state().baseLevels();
    }

    /** Returns a complete management snapshot for one logger name. */
    public LoggerLevelSnapshot getLoggerLevel(String loggerName) {
        String normalized = normalize(loggerName);
        return state().logger(normalized);
    }

    public Map<String, LoggerLevelSnapshot> listLoggerLevels() {
        LoggerManagementState state = state();
        LinkedHashMap<String, LoggerLevelSnapshot> levels = new LinkedHashMap<>();
        for (String name : state.knownNames()) {
            levels.put(name, state.logger(name));
        }
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

    private LoggerManagementState state() {
        return LoggerManagementState.capture(runtime);
    }

    private static String normalize(String loggerName) {
        String normalized = Objects.requireNonNull(loggerName, "loggerName").trim();
        return ROOT_LOGGER_NAME.equalsIgnoreCase(normalized) ? ROOT_LOGGER_NAME : normalized;
    }
}
