package com.logyard4j.logyard.api.diagnostics;

import com.logyard4j.logyard.api.Level;

import java.util.List;
import java.util.Objects;

/**
 * Immutable explanation of the configuration selected for one logger name.
 *
 * <p>This snapshot describes routing policy. It does not predict processor filtering, output
 * failures, overload drops, or runtime shutdown. A disabled override retains the underlying
 * configured threshold in {@link #level()} while {@link #enabled()} reports false.</p>
 *
 * @param loggerName resolved logger name
 * @param level effective minimum level, or the configured threshold when disabled
 * @param outputs ordered output names
 * @param processors ordered enricher and filter names
 * @param matchedRule configuration rule that supplied the route
 * @param enabled whether the route's level policy allows events
 */
public record EffectiveRoute(
        String loggerName,
        Level level,
        List<String> outputs,
        List<String> processors,
        String matchedRule,
        boolean enabled) {
    /** Detaches the route from caller-owned collections. */
    public EffectiveRoute {
        Objects.requireNonNull(loggerName, "loggerName");
        Objects.requireNonNull(level, "level");
        outputs = List.copyOf(outputs);
        processors = List.copyOf(processors);
        Objects.requireNonNull(matchedRule, "matchedRule");
    }

    /**
     * Creates an enabled route with the supplied threshold.
     *
     * @param loggerName resolved logger name
     * @param level effective minimum level
     * @param outputs ordered output names
     * @param processors ordered enricher and filter names
     * @param matchedRule configuration rule that supplied the route
     */
    public EffectiveRoute(String loggerName, Level level, List<String> outputs, List<String> processors, String matchedRule) {
        this(loggerName, level, outputs, processors, matchedRule, true);
    }

    /**
     * Tests an event level against this snapshot's threshold and operational override.
     *
     * @param candidate event level to test
     * @return whether the level policy enables the event
     */
    public boolean isEnabled(Level candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return enabled && level.enables(candidate);
    }
}
