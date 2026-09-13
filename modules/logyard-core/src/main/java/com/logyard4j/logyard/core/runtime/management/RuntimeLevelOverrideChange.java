package com.logyard4j.logyard.core.runtime.management;

import com.logyard4j.logyard.core.routing.CompiledRoute;

import java.util.Map;

/** A prepared level-override generation and the affected logger routes it must replace. */
public record RuntimeLevelOverrideChange(
        RuntimeGeneration generation,
        Map<String, CompiledRoute> compiledRoutes) {
}
