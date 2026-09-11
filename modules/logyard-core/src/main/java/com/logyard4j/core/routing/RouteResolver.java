package com.logyard4j.core.routing;

import java.util.Map;
import java.util.Objects;

/** Applies hierarchical logger rules to produce one fully inherited route. */
public final class RouteResolver {
    private RouteResolver() {
    }

    /**
     * Resolves a logger route by overlaying every matching rule onto the root route.
     *
     * @param loggerName fully qualified logger name
     * @param root root route
     * @param rules logger rules keyed by exact name or package-like prefix
     * @return fully inherited route and the most specific rule that contributed to it
     */
    public static ResolvedRoute resolve(String loggerName, RouteDefinition root, Map<String, RouteDefinition> rules) {
        RouteDefinition effective = Objects.requireNonNull(root, "root");
        String matchedRule = "root";
        var matchingRules = LoggerNameHierarchy.matchingRules(loggerName, rules);

        for (LoggerNameHierarchy.Match<RouteDefinition> match : matchingRules) {
            effective = overlay(effective, match.rule());
            matchedRule = match.name();
        }

        return new ResolvedRoute(effective, matchedRule);
    }

    private static RouteDefinition overlay(RouteDefinition parent, RouteDefinition child) {
        return new RouteDefinition(
                child.level() == null ? parent.level() : child.level(),
                child.outputs() == null ? parent.outputs() : child.outputs(),
                child.processors() == null ? parent.processors() : child.processors());
    }
}
