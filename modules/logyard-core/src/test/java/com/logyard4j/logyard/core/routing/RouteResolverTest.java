package com.logyard4j.logyard.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.logyard4j.logyard.api.Level;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RouteResolverTest {
    @Test
    void overlaysMatchingRulesFromLeastToMostSpecific() {
        RouteDefinition root = RouteDefinition.root(Level.INFO, List.of("console"), List.of("context"));
        Map<String, RouteDefinition> rules = Map.of(
                "com.acme", new RouteDefinition(Level.DEBUG, null, List.of("sample")),
                "com.acme.noisy", new RouteDefinition(Level.WARN, List.of("json"), null),
                "org.example", new RouteDefinition(Level.ERROR, List.of("ignored"), List.of()));

        ResolvedRoute resolved = RouteResolver.resolve("com.acme.noisy.Worker", root, rules);

        assertEquals(Level.WARN, resolved.definition().level());
        assertEquals(List.of("json"), resolved.definition().outputs());
        assertEquals(List.of("sample"), resolved.definition().processors());
        assertEquals("com.acme.noisy", resolved.matchedRule());
    }

    @Test
    void returnsRootWhenNoLoggerRuleMatches() {
        RouteDefinition root = RouteDefinition.root(Level.INFO, List.of("console"), List.of());

        ResolvedRoute resolved = RouteResolver.resolve(
                "com.example.Worker",
                root,
                Map.of("org.example", new RouteDefinition(Level.DEBUG, null, null)));

        assertEquals(root, resolved.definition());
        assertEquals("root", resolved.matchedRule());
    }

    @Test
    void matchesOnlyCompleteLoggerNameSegments() {
        RouteDefinition root = RouteDefinition.root(Level.INFO, List.of("console"), List.of());
        RouteDefinition similarPrefix = new RouteDefinition(Level.ERROR, List.of("ignored"), List.of());

        ResolvedRoute resolved = RouteResolver.resolve("com.acmetoo.Worker", root, Map.of("com.acme", similarPrefix));

        assertEquals(root, resolved.definition());
        assertEquals("root", resolved.matchedRule());
    }
}
