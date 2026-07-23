package com.zsumz.logyard.core.routing;

import java.util.Comparator;
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

        return rules.entrySet().stream()
                .filter(entry -> matches(loggerName, entry.getKey()))
                .sorted(Comparator.comparingInt((Map.Entry<String, T> entry) -> entry.getKey().length())
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> new Match<>(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static boolean matches(String loggerName, String ruleName) {
        return loggerName.equals(ruleName) || loggerName.startsWith(ruleName + ".");
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
