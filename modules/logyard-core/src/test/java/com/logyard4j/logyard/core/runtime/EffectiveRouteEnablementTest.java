package com.logyard4j.logyard.core.runtime;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.diagnostics.EffectiveRoute;
import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.core.level.RuntimeLevelOverride;
import com.logyard4j.logyard.core.routing.RouteDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EffectiveRouteEnablementTest {
    @Test
    void explanationsUseThePublicLoggerNameContract() {
        try (DefaultLogyardRuntime runtime = runtime()) {
            String maximum = "a".repeat(CaptureLimits.MAX_NAME_CHARS);
            String oversized = maximum + "a";

            assertEquals(maximum, runtime.explain(maximum).loggerName());
            IllegalArgumentException loggerFailure = assertThrows(
                    IllegalArgumentException.class, () -> runtime.logger(oversized));
            IllegalArgumentException explanationFailure = assertThrows(
                    IllegalArgumentException.class, () -> runtime.explain(oversized));
            assertEquals(loggerFailure.getMessage(), explanationFailure.getMessage());
            assertThrows(IllegalArgumentException.class, () -> runtime.explain(" \t"));
        }
    }

    @Test
    void explanationsFollowInheritedOffOverridesAndRemainImmutableAfterReenablement() {
        try (DefaultLogyardRuntime runtime = runtime()) {
            String logger = "app.orders.Repository";
            EffectiveRoute initial = assertMatchesLogger(runtime, logger);
            assertTrue(initial.enabled());

            runtime.setLevelOverride("app.orders", RuntimeLevelOverride.off());
            EffectiveRoute disabled = assertMatchesLogger(runtime, logger);
            assertFalse(disabled.enabled());
            assertEquals(Level.WARN, disabled.level());
            assertTrue(initial.enabled());
            assertTrue(runtime.explain("other").enabled());

            runtime.setLevelOverride(logger, RuntimeLevelOverride.threshold(Level.DEBUG));
            EffectiveRoute enabled = assertMatchesLogger(runtime, logger);
            assertTrue(enabled.isEnabled(Level.DEBUG));
            assertFalse(enabled.isEnabled(Level.TRACE));
            assertFalse(disabled.enabled());

            runtime.clearLevelOverride(logger);
            assertFalse(assertMatchesLogger(runtime, logger).enabled());
            runtime.clearAllLevelOverrides();
            assertEquals(initial, assertMatchesLogger(runtime, logger));
        }
    }

    private static EffectiveRoute assertMatchesLogger(DefaultLogyardRuntime runtime, String name) {
        EffectiveRoute route = runtime.explain(name);
        for (Level level : Level.values()) {
            assertEquals(runtime.logger(name).isEnabled(level), route.isEnabled(level), name + " / " + level);
        }
        return route;
    }

    private static DefaultLogyardRuntime runtime() {
        return new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("capture"), List.of()),
                Map.of("app", new RouteDefinition(Level.WARN, null, null)),
                Map.of("capture", ignored -> { }), Map.of()));
    }
}
