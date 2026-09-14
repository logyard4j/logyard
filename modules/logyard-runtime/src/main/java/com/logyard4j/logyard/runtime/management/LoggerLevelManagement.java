package com.logyard4j.logyard.runtime.management;

import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.core.level.RuntimeLevelOverrides;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Framework-neutral control plane for temporary hierarchical logger-level overrides. */
public final class LoggerLevelManagement {
    /** Canonical name used to address the root logger. */
    public static final String ROOT_LOGGER_NAME = RuntimeLevelOverrides.ROOT_LOGGER_NAME;

    private final DefaultLogyardRuntime runtime;

    private LoggerLevelManagement(DefaultLogyardRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Creates a management view over a standard Logyard runtime.
     *
     * @param runtime runtime to manage
     * @return runtime management view
     * @throws IllegalArgumentException when the runtime is not Logyard's standard implementation
     */
    public static LoggerLevelManagement forRuntime(LogyardRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        if (runtime instanceof DefaultLogyardRuntime managed) {
            return new LoggerLevelManagement(managed);
        }
        throw new IllegalArgumentException("logger-level management requires the standard Logyard runtime");
    }

    /**
     * Returns the effective threshold after runtime overrides and configuration inheritance.
     *
     * @param loggerName logger name or {@link #ROOT_LOGGER_NAME}
     * @return effective logger threshold
     */
    public LoggerLevel getEffectiveLevel(String loggerName) {
        String normalized = normalize(loggerName);
        return LoggerManagementState.logger(runtime.loggerLevelSnapshot(normalized)).effectiveLevel();
    }

    /**
     * Returns exact temporary runtime overrides.
     *
     * @return immutable logger-name-to-override mapping
     */
    public Map<String, LoggerLevel> listConfiguredLevels() {
        return state().overrides();
    }

    /**
     * Returns exact thresholds from the active base configuration, including {@code ROOT}.
     *
     * @return immutable logger-name-to-base-threshold mapping
     */
    public Map<String, LoggerLevel> listBaseConfiguredLevels() {
        return state().baseLevels();
    }

    /**
     * Returns a complete management snapshot for one logger name.
     *
     * @param loggerName logger name or {@link #ROOT_LOGGER_NAME}
     * @return point-in-time logger-level snapshot
     */
    public LoggerLevelSnapshot getLoggerLevel(String loggerName) {
        String normalized = normalize(loggerName);
        return LoggerManagementState.logger(runtime.loggerLevelSnapshot(normalized));
    }

    /**
     * Returns snapshots for every configured, overridden, or observed logger name.
     *
     * @return immutable sorted logger-name-to-snapshot mapping
     */
    public Map<String, LoggerLevelSnapshot> listLoggerLevels() {
        LoggerManagementState state = state();
        LinkedHashMap<String, LoggerLevelSnapshot> levels = new LinkedHashMap<>();
        for (String name : state.knownNames()) {
            levels.put(name, state.logger(name));
        }
        return Collections.unmodifiableMap(levels);
    }

    /**
     * Sets an exact temporary runtime override, or clears it when {@code level} is {@code null}.
     *
     * @param loggerName logger name or {@link #ROOT_LOGGER_NAME}
     * @param level override threshold, {@link LoggerLevel#OFF}, or {@code null} to clear
     */
    public void setLevel(String loggerName, LoggerLevel level) {
        if (level == null) {
            clearLevel(loggerName);
            return;
        }
        runtime.setLevelOverride(loggerName, level.toOverride());
    }

    /**
     * Clears the exact temporary runtime override for one logger.
     *
     * @param loggerName logger name or {@link #ROOT_LOGGER_NAME}
     */
    public void clearLevel(String loggerName) {
        runtime.clearLevelOverride(loggerName);
    }

    /** Clears every temporary runtime override. */
    public void clearAllOverrides() {
        runtime.clearAllLevelOverrides();
    }

    private LoggerManagementState state() {
        return LoggerManagementState.capture(runtime);
    }

    private static String normalize(String loggerName) {
        Objects.requireNonNull(loggerName, "loggerName");
        int start = 0;
        int end = loggerName.length();
        while (start < end && loggerName.charAt(start) <= ' ') start++;
        while (start < end && loggerName.charAt(end - 1) <= ' ') end--;
        if (end - start > CaptureLimits.MAX_NAME_CHARS) {
            throw new IllegalArgumentException(
                    "loggerName exceeds " + CaptureLimits.MAX_NAME_CHARS + " characters");
        }
        String normalized = loggerName.substring(start, end);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("loggerName must not be blank");
        }
        return ROOT_LOGGER_NAME.equalsIgnoreCase(normalized) ? ROOT_LOGGER_NAME : normalized;
    }
}
