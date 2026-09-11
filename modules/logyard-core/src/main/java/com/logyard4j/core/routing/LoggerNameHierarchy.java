package com.logyard4j.core.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Finds logger rules from the least specific matching name to the most specific one. */
public final class LoggerNameHierarchy {
    private LoggerNameHierarchy() {
    }

    /**
     * Returns every rule inherited by the logger in application order.
     *
     * @param loggerName fully qualified logger name
     * @param rules logger rules keyed by exact name or package-like prefix
     * @param <T> rule value type
     * @return matching rules ordered from least to most specific
     */
    public static <T> List<Match<T>> matchingRules(String loggerName, Map<String, T> rules) {
        Objects.requireNonNull(loggerName, "loggerName");
        Objects.requireNonNull(rules, "rules");
        if (loggerName.isBlank()) {
            throw new IllegalArgumentException("logger name must not be blank");
        }

        List<Match<T>> matches = new ArrayList<>();
        int separator = loggerName.indexOf('.');
        while (separator >= 0) {
            addIfPresent(matches, loggerName.substring(0, separator), rules);
            separator = loggerName.indexOf('.', separator + 1);
        }
        addIfPresent(matches, loggerName, rules);
        return List.copyOf(matches);
    }

    private static <T> void addIfPresent(
            List<Match<T>> matches,
            String name,
            Map<String, T> rules) {
        if (rules.containsKey(name)) {
            matches.add(new Match<>(name, rules.get(name)));
        }
    }

    /**
     * One matching logger rule.
     *
     * @param name configured logger name
     * @param rule configured rule value
     * @param <T> rule value type
     */
    public record Match<T>(String name, T rule) {
        public Match {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(rule, "rule");
        }
    }
}
