package com.logyard4j.core.runtime;

import com.logyard4j.api.Level;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.routing.RouteDefinition;

import java.util.List;
import java.util.Map;

/** Small programmatic runtime-plan factories used by embedders and tests. */
final class RuntimePlans {
    private RuntimePlans() {
    }

    static RuntimePlan consoleOnly(EventSink sink) {
        return new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("console"), List.of()),
                Map.of(),
                Map.of("console", sink),
                Map.of());
    }
}
