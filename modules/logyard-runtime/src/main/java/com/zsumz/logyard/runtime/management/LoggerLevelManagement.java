package com.zsumz.logyard.runtime.management;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.core.level.RuntimeLevelOverride;
import com.zsumz.logyard.core.level.RuntimeLevelOverrides;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.runtime.assembly.RuntimeAssembly;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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

    /** Returns exact thresholds from the active base configuration, including {@code ROOT}. */
    public Map<String, LoggerLevel> listBaseConfiguredLevels() {
        LinkedHashMap<String, LoggerLevel> levels = new LinkedHashMap<>();
        RuntimeAssembly assembly = LogyardRuntimeFactory.assemblyFor(runtime);
        if (assembly == null) {
            runtime.baseConfiguredLevels().forEach((logger, level) -> levels.put(logger, LoggerLevel.from(level)));
        } else {
            levels.put(ROOT_LOGGER_NAME, LoggerLevel.from(assembly.config().rootLogger().level()));
            assembly.config().loggers().forEach((logger, rule) -> {
                if (rule.level() != null) {
                    levels.put(logger, LoggerLevel.from(rule.level()));
                }
            });
        }
        return Collections.unmodifiableMap(levels);
    }

    /** Returns a complete management snapshot for one logger name. */
    public LoggerLevelSnapshot getLoggerLevel(String loggerName) {
        String normalized = normalize(loggerName);
        Map<String, LoggerLevel> overrides = listConfiguredLevels();
        Map<String, LoggerLevel> base = listBaseConfiguredLevels();
        LoggerLevel runtimeOverride = overrides.get(normalized);
        LoggerLevel baseConfigured = base.get(normalized);
        LoggerLevel configured = runtimeOverride == null ? baseConfigured : runtimeOverride;
        LoggerLevelOrigin origin = runtime.effectiveLevelOverride(normalized) != null
                ? LoggerLevelOrigin.RUNTIME_OVERRIDE
                : baseConfigured == null ? LoggerLevelOrigin.INHERITED : LoggerLevelOrigin.BASE_CONFIGURATION;
        return new LoggerLevelSnapshot(
                configured,
                getEffectiveLevel(normalized),
                baseConfigured,
                runtimeOverride,
                origin);
    }

    public Map<String, LoggerLevelSnapshot> listLoggerLevels() {
        Set<String> names = new TreeSet<>(runtime.knownLoggerNames());
        names.add(ROOT_LOGGER_NAME);
        LinkedHashMap<String, LoggerLevelSnapshot> levels = new LinkedHashMap<>();
        for (String name : names) {
            levels.put(name, getLoggerLevel(name));
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

    private static String normalize(String loggerName) {
        String normalized = Objects.requireNonNull(loggerName, "loggerName").trim();
        return ROOT_LOGGER_NAME.equalsIgnoreCase(normalized) ? ROOT_LOGGER_NAME : normalized;
    }
}
