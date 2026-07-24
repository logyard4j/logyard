package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.routing.RouteDefinition;

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
