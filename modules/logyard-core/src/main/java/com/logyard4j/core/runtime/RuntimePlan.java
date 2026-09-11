package com.logyard4j.core.runtime;

import com.logyard4j.api.Level;
import com.logyard4j.api.spi.processing.EventProcessor;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.level.RuntimeLevelOverrides;
import com.logyard4j.core.routing.RouteDefinition;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable validated graph ready for route compilation. */
public final class RuntimePlan {
    private final RouteDefinition root;
    private final Map<String, RouteDefinition> loggers;
    private final Map<String, EventSink> outputs;
    private final Map<String, EventProcessor> processors;
    private final Map<String, Level> configuredLevels;
    private final Duration shutdownTimeout;

    public RuntimePlan(
            RouteDefinition root,
            Map<String, RouteDefinition> loggers,
            Map<String, EventSink> outputs,
            Map<String, EventProcessor> processors) {
        this(root, loggers, outputs, processors, Duration.ofSeconds(3), inferredConfiguredLevels(root, loggers));
    }

    public RuntimePlan(
            RouteDefinition root,
            Map<String, RouteDefinition> loggers,
            Map<String, EventSink> outputs,
            Map<String, EventProcessor> processors,
            Duration shutdownTimeout) {
        this(root, loggers, outputs, processors, shutdownTimeout, inferredConfiguredLevels(root, loggers));
    }

    public RuntimePlan(
            RouteDefinition root,
            Map<String, RouteDefinition> loggers,
            Map<String, EventSink> outputs,
            Map<String, EventProcessor> processors,
            Duration shutdownTimeout,
            Map<String, Level> configuredLevels) {
        this.root = Objects.requireNonNull(root, "root");
        this.loggers = orderedCopy(loggers, "loggers");
        this.outputs = orderedCopy(outputs, "outputs");
        this.processors = orderedCopy(processors, "processors");
        this.configuredLevels = orderedCopy(configuredLevels, "configuredLevels");
        validateNamedValues(this.loggers, "logger rule");
        validateNamedValues(this.outputs, "output");
        validateNamedValues(this.processors, "processor");
        validateNamedValues(this.configuredLevels, "configured logger level");
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdown timeout must not be negative");
        }
        validateReferences();
    }

    public RouteDefinition root() { return root; }
    public Map<String, RouteDefinition> loggers() { return loggers; }
    public Map<String, EventSink> outputs() { return outputs; }
    public Map<String, EventProcessor> processors() { return processors; }
    public Map<String, Level> configuredLevels() { return configuredLevels; }
    public Duration shutdownTimeout() { return shutdownTimeout; }

    private static <K, V> Map<K, V> orderedCopy(Map<K, V> source, String name) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(source, name)));
    }

    private static Map<String, Level> inferredConfiguredLevels(
            RouteDefinition root,
            Map<String, RouteDefinition> loggers) {
        LinkedHashMap<String, Level> levels = new LinkedHashMap<>();
        levels.put(RuntimeLevelOverrides.ROOT_LOGGER_NAME, Objects.requireNonNull(root, "root").level());
        Objects.requireNonNull(loggers, "loggers").forEach((name, definition) -> {
            if (definition != null && definition.level() != null) {
                levels.put(name, definition.level());
            }
        });
        return levels;
    }

    private void validateReferences() {
        if (root.level() == null || root.outputs() == null || root.processors() == null) {
            throw new IllegalArgumentException("root route requires level, outputs, and processors");
        }
        validateRoute("root", root);
        loggers.forEach((name, definition) -> {
            if (name.isBlank()) {
                throw new IllegalArgumentException("logger rule name must not be blank");
            }
            validateRoute(name, definition);
        });
    }

    private void validateRoute(String name, RouteDefinition definition) {
        Objects.requireNonNull(definition, "route definition for " + name);
        if (definition.outputs() != null) {
            Set<String> seen = new LinkedHashSet<>();
            for (String output : definition.outputs()) {
                if (!seen.add(output)) {
                    throw new IllegalArgumentException(
                            "route '" + name + "' repeats output '" + output + "'");
                }
                if (!outputs.containsKey(output)) {
                    throw new IllegalArgumentException(
                            "route '" + name + "' references unknown output '" + output + "'");
                }
            }
        }
        if (definition.processors() != null) {
            Set<String> seen = new LinkedHashSet<>();
            for (String processor : definition.processors()) {
                if (!seen.add(processor)) {
                    throw new IllegalArgumentException(
                            "route '" + name + "' repeats processor '" + processor + "'");
                }
                if (!processors.containsKey(processor)) {
                    throw new IllegalArgumentException(
                            "route '" + name + "' references unknown processor '" + processor + "'");
                }
            }
        }
    }

    private static void validateNamedValues(Map<String, ?> values, String kind) {
        values.forEach((name, value) -> {
            Objects.requireNonNull(name, kind + " name");
            Objects.requireNonNull(value, kind + " '" + name + "'");
            if (name.isBlank()) {
                throw new IllegalArgumentException(kind + " name must not be blank");
            }
        });
    }
}
