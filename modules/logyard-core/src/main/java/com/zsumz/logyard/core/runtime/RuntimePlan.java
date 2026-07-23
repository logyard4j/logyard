package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogBuilder;
import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.EventProcessor;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.core.delivery.CompositeSink;
import com.zsumz.logyard.core.diagnostics.EmergencyText;
import com.zsumz.logyard.core.routing.CompiledRoute;
import com.zsumz.logyard.api.diagnostics.EffectiveRoute;
import com.zsumz.logyard.core.routing.PlanEpoch;
import com.zsumz.logyard.core.routing.RouteDefinition;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/** Immutable validated graph ready for route compilation. */
public final class RuntimePlan {
    private final RouteDefinition root;
    private final Map<String, RouteDefinition> loggers;
    private final Map<String, EventSink> outputs;
    private final Map<String, EventProcessor> processors;
    private final Duration shutdownTimeout;

    public RuntimePlan(
            RouteDefinition root,
            Map<String, RouteDefinition> loggers,
            Map<String, EventSink> outputs,
            Map<String, EventProcessor> processors) {
        this(root, loggers, outputs, processors, Duration.ofSeconds(3));
    }

    public RuntimePlan(
            RouteDefinition root,
            Map<String, RouteDefinition> loggers,
            Map<String, EventSink> outputs,
            Map<String, EventProcessor> processors,
            Duration shutdownTimeout) {
        this.root = Objects.requireNonNull(root, "root");
        this.loggers = orderedCopy(loggers, "loggers");
        this.outputs = orderedCopy(outputs, "outputs");
        this.processors = orderedCopy(processors, "processors");
        validateNamedValues(this.loggers, "logger rule");
        validateNamedValues(this.outputs, "output");
        validateNamedValues(this.processors, "processor");
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
    public Duration shutdownTimeout() { return shutdownTimeout; }

    private static <K, V> Map<K, V> orderedCopy(Map<K, V> source, String name) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(source, name)));
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
