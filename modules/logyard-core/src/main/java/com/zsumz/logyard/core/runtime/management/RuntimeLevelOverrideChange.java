package com.zsumz.logyard.core.runtime.management;

import com.zsumz.logyard.core.routing.CompiledRoute;

import java.util.Map;

/** A prepared level-override generation and the affected logger routes it must replace. */
public record RuntimeLevelOverrideChange(
        RuntimeGeneration generation,
        Map<String, CompiledRoute> compiledRoutes) {
}
