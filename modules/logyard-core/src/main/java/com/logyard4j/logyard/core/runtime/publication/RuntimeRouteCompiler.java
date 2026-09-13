package com.logyard4j.logyard.core.runtime.publication;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.spi.processing.EventProcessor;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.delivery.CompositeSink;
import com.logyard4j.logyard.core.level.RuntimeLevelOverride;
import com.logyard4j.logyard.core.level.RuntimeLevelOverrides;
import com.logyard4j.logyard.core.routing.CompiledRoute;
import com.logyard4j.logyard.core.routing.PlanEpoch;
import com.logyard4j.logyard.core.routing.ResolvedRoute;
import com.logyard4j.logyard.core.routing.RouteDefinition;
import com.logyard4j.logyard.core.routing.RouteResolver;
import com.logyard4j.logyard.core.runtime.RuntimePlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compiles one resolved logger rule into its immutable hot-path route. */
final class RuntimeRouteCompiler {
    private RuntimeRouteCompiler() {
    }

    static CompiledRoute compile(
            String loggerName,
            RuntimePlan plan,
            RuntimeLevelOverrides overrides,
            PlanEpoch epoch) {
        ResolvedRoute resolved = RouteResolver.resolve(loggerName, plan.root(), plan.loggers());
        RouteDefinition effective = resolved.definition();
        RuntimeLevelOverride override = overrides.resolve(loggerName);
        LevelSelection level = LevelSelection.resolve(
                Objects.requireNonNull(effective.level(), "effective level"),
                override);

        List<String> outputNames = Objects.requireNonNull(effective.outputs(), "effective outputs");
        Map<String, EventSink> sinks = new LinkedHashMap<>();
        for (String output : outputNames) {
            sinks.put(output, plan.outputs().get(output));
        }

        List<String> processorNames = Objects.requireNonNull(effective.processors(), "effective processors");
        EventProcessor[] processors = new EventProcessor[processorNames.size()];
        for (int index = 0; index < processorNames.size(); index++) {
            processors[index] = plan.processors().get(processorNames.get(index));
        }

        return new CompiledRoute(
                level.displayLevel(),
                level.enabledMask(),
                new CompositeSink(sinks),
                processors,
                List.copyOf(outputNames),
                List.copyOf(processorNames),
                resolved.matchedRule(),
                epoch);
    }

    private record LevelSelection(Level displayLevel, int enabledMask) {
        static LevelSelection resolve(
                Level configuredLevel,
                RuntimeLevelOverride override) {
            if (override == null) {
                return new LevelSelection(configuredLevel, Level.enabledMaskFrom(configuredLevel));
            }
            return override.disabled()
                    ? new LevelSelection(configuredLevel, 0)
                    : new LevelSelection(override.threshold(), override.enabledMask());
        }
    }
}
