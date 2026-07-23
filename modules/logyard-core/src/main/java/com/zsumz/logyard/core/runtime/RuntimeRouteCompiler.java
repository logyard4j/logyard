package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.spi.EventProcessor;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.core.delivery.CompositeSink;
import com.zsumz.logyard.core.routing.CompiledRoute;
import com.zsumz.logyard.core.routing.PlanEpoch;
import com.zsumz.logyard.core.routing.ResolvedRoute;
import com.zsumz.logyard.core.routing.RouteDefinition;
import com.zsumz.logyard.core.routing.RouteResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Compiles one resolved logger rule into its immutable hot-path route. */
final class RuntimeRouteCompiler {
    private RuntimeRouteCompiler() {
    }

    static CompiledRoute compile(String loggerName, RuntimePlan plan, PlanEpoch epoch) {
        ResolvedRoute resolved = RouteResolver.resolve(loggerName, plan.root(), plan.loggers());
        RouteDefinition effective = resolved.definition();

        List<String> outputNames = Objects.requireNonNull(effective.outputs(), "effective outputs");
        List<EventSink> sinks = new ArrayList<>(outputNames.size());
        for (String output : outputNames) {
            sinks.add(plan.outputs().get(output));
        }

        List<String> processorNames = Objects.requireNonNull(effective.processors(), "effective processors");
        EventProcessor[] processors = new EventProcessor[processorNames.size()];
        for (int index = 0; index < processorNames.size(); index++) {
            processors[index] = plan.processors().get(processorNames.get(index));
        }

        return new CompiledRoute(
                Objects.requireNonNull(effective.level(), "effective level"),
                new CompositeSink(sinks),
                processors,
                List.copyOf(outputNames),
                List.copyOf(processorNames),
                resolved.matchedRule(),
                epoch);
    }
}
