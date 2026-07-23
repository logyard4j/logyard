package com.zsumz.logyard.core.level;

import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.core.routing.LoggerNameHierarchy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable hierarchical runtime-level overrides with a reserved root key. */
public final class RuntimeLevelOverrides {
    public static final String ROOT_LOGGER_NAME = "ROOT";

    private static final RuntimeLevelOverrides EMPTY = new RuntimeLevelOverrides(null, Map.of());

    private final RuntimeLevelOverride root;
    private final Map<String, RuntimeLevelOverride> loggers;

    private RuntimeLevelOverrides(
            RuntimeLevelOverride root,
            Map<String, RuntimeLevelOverride> loggers) {
        this.root = root;
        this.loggers = Collections.unmodifiableMap(new TreeMap<>(loggers));
    }

    public static RuntimeLevelOverrides empty() {
        return EMPTY;
    }

    public RuntimeLevelOverrides withLevel(String loggerName, RuntimeLevelOverride override) {
        String normalized = loggerName(loggerName);
        RuntimeLevelOverride value = Objects.requireNonNull(override, "override");
        if (ROOT_LOGGER_NAME.equals(normalized)) {
            return value.equals(root) ? this : new RuntimeLevelOverrides(value, loggers);
        }
        if (value.equals(loggers.get(normalized))) {
            return this;
        }
        TreeMap<String, RuntimeLevelOverride> copy = new TreeMap<>(loggers);
        copy.put(normalized, value);
        return new RuntimeLevelOverrides(root, copy);
    }

    public RuntimeLevelOverrides withoutLevel(String loggerName) {
        String normalized = loggerName(loggerName);
        if (ROOT_LOGGER_NAME.equals(normalized)) {
            return root == null ? this : new RuntimeLevelOverrides(null, loggers);
        }
        if (!loggers.containsKey(normalized)) {
            return this;
        }
        TreeMap<String, RuntimeLevelOverride> copy = new TreeMap<>(loggers);
        copy.remove(normalized);
        return copy.isEmpty() && root == null ? EMPTY : new RuntimeLevelOverrides(root, copy);
    }

    public RuntimeLevelOverrides clear() {
        return isEmpty() ? this : EMPTY;
    }

    public RuntimeLevelOverride resolve(String loggerName) {
        RuntimeLevelOverride resolved = root;
        for (LoggerNameHierarchy.Match<RuntimeLevelOverride> match : LoggerNameHierarchy.matchingRules(loggerName, loggers)) {
            resolved = match.rule();
        }
        return resolved;
    }

    public Map<String, RuntimeLevelOverride> configuredLevels() {
        if (root == null) {
            return loggers;
        }
        LinkedHashMap<String, RuntimeLevelOverride> configured = new LinkedHashMap<>();
        configured.put(ROOT_LOGGER_NAME, root);
        configured.putAll(loggers);
        return Collections.unmodifiableMap(configured);
    }

    public boolean affects(String configuredLogger, String existingLogger) {
        String normalized = loggerName(configuredLogger);
        Objects.requireNonNull(existingLogger, "existingLogger");
        return ROOT_LOGGER_NAME.equals(normalized)
                || existingLogger.equals(normalized)
                || existingLogger.startsWith(normalized + ".");
    }

    public boolean isEmpty() {
        return root == null && loggers.isEmpty();
    }

    private static String loggerName(String value) {
        String normalized = Objects.requireNonNull(value, "loggerName").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("loggerName must not be blank");
        }
        if (normalized.length() > CaptureLimits.MAX_NAME_CHARS) {
            throw new IllegalArgumentException("loggerName exceeds " + CaptureLimits.MAX_NAME_CHARS + " characters");
        }
        return ROOT_LOGGER_NAME.equalsIgnoreCase(normalized) ? ROOT_LOGGER_NAME : normalized;
    }
}
