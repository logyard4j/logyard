package com.zsumz.logyard.core.runtime.publication;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.spi.processing.EventProcessor;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.delivery.CompositeSink;
import com.zsumz.logyard.core.level.RuntimeLevelOverride;
import com.zsumz.logyard.core.level.RuntimeLevelOverrides;
import com.zsumz.logyard.core.routing.CompiledRoute;
import com.zsumz.logyard.core.routing.PlanEpoch;
import com.zsumz.logyard.core.routing.ResolvedRoute;
import com.zsumz.logyard.core.routing.RouteDefinition;
import com.zsumz.logyard.core.routing.RouteResolver;
import com.zsumz.logyard.core.runtime.RuntimePlan;

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
