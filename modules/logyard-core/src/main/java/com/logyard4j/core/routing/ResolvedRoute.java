package com.logyard4j.core.routing;

import java.util.Objects;

/**
 * A fully inherited route and the most specific logger rule that contributed to it.
 *
 * @param definition resolved route definition
 * @param matchedRule most specific matching rule, or {@code root}
 */
public record ResolvedRoute(RouteDefinition definition, String matchedRule) {
    public ResolvedRoute {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(matchedRule, "matchedRule");
    }
}
