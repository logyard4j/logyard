package com.zsumz.logyard.api.diagnostics;

import com.zsumz.logyard.api.Level;

import java.util.List;
import java.util.Objects;

/**
 * Immutable explanation of the configuration selected for one logger name.
 *
 * @param loggerName resolved logger name
 * @param level effective minimum level
 * @param outputs ordered output names
 * @param processors ordered enricher and filter names
 * @param matchedRule configuration rule that supplied the route
 */
public record EffectiveRoute(
        String loggerName,
        Level level,
        List<String> outputs,
        List<String> processors,
        String matchedRule) {
    /** Detaches the route from caller-owned collections. */
    public EffectiveRoute {
        Objects.requireNonNull(loggerName, "loggerName");
        Objects.requireNonNull(level, "level");
        outputs = List.copyOf(outputs);
        processors = List.copyOf(processors);
        Objects.requireNonNull(matchedRule, "matchedRule");
    }
}
