package com.logyard4j.logyard.runtime.assembly.routing;

import com.logyard4j.logyard.config.logging.LoggerRuleConfig;
import com.logyard4j.logyard.core.routing.LoggerNameHierarchy;

import java.util.Map;
import java.util.Objects;

/** Resolves independently inheritable logger configuration fields before runtime assembly. */
public final class ConfiguredLoggerRuleResolver {
    private ConfiguredLoggerRuleResolver() {
    }

    /**
     * Resolves a logger rule by overlaying every matching rule onto the root rule.
     *
     * @param loggerName fully qualified logger name
     * @param root root logger rule
     * @param rules logger rules keyed by exact name or package-like prefix
     * @return fully inherited rule and the most specific rule that contributed to it
     */
    public static ResolvedRule resolve(String loggerName, LoggerRuleConfig root, Map<String, LoggerRuleConfig> rules) {
        LoggerRuleConfig effective = Objects.requireNonNull(root, "root");
        String matchedRule = "root";
        var matchingRules = LoggerNameHierarchy.matchingRules(loggerName, rules);

        for (LoggerNameHierarchy.Match<LoggerRuleConfig> match : matchingRules) {
            effective = overlay(effective, match.rule());
            matchedRule = match.name();
        }

        return new ResolvedRule(effective, matchedRule);
    }

    private static LoggerRuleConfig overlay(LoggerRuleConfig parent, LoggerRuleConfig child) {
        return new LoggerRuleConfig(
                child.level() == null ? parent.level() : child.level(),
                child.outputs() == null ? parent.outputs() : child.outputs(),
                child.enrich() == null ? parent.enrich() : child.enrich(),
                child.filters() == null ? parent.filters() : child.filters());
    }

    /**
     * One fully inherited logger rule.
     *
     * @param rule resolved rule
     * @param matchedRule most specific matching rule, or {@code root}
     */
    public record ResolvedRule(LoggerRuleConfig rule, String matchedRule) {
        public ResolvedRule {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(matchedRule, "matchedRule");
        }
    }
}
