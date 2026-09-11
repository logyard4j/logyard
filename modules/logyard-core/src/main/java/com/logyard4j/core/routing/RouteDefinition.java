package com.logyard4j.core.routing;

import com.logyard4j.api.Level;
import com.logyard4j.api.spi.processing.EventProcessor;
import com.logyard4j.api.spi.output.EventSink;

import java.util.List;

/** Nullable fields on non-root definitions inherit from the nearest matching parent. */
public record RouteDefinition(Level level, List<String> outputs, List<String> processors) {
    public RouteDefinition {
        outputs = outputs == null ? null : List.copyOf(outputs);
        processors = processors == null ? null : List.copyOf(processors);
    }

    public static RouteDefinition root(Level level, List<String> outputs, List<String> processors) {
        if (level == null || outputs == null || processors == null) {
            throw new IllegalArgumentException("root route requires level, outputs, and processors");
        }
        return new RouteDefinition(level, outputs, processors);
    }
}
