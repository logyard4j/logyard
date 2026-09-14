package com.logyard4j.logyard.api.diagnostics;

import com.logyard4j.logyard.api.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EffectiveRouteTest {
    @Test
    void existingConstructorCreatesAnEnabledThresholdSnapshot() {
        for (Level threshold : Level.values()) {
            EffectiveRoute route = new EffectiveRoute("test", threshold, List.of("output"), List.of(), "root");
            assertTrue(route.enabled());
            for (Level candidate : Level.values()) {
                assertEquals(threshold.enables(candidate), route.isEnabled(candidate));
            }
        }
    }

    @Test
    void disabledPolicyRetainsTheUnderlyingThresholdAndRejectsEveryLevel() {
        EffectiveRoute route = new EffectiveRoute("test", Level.INFO, List.of("output"), List.of(), "root", false);
        assertEquals(Level.INFO, route.level());
        assertFalse(route.enabled());
        for (Level candidate : Level.values()) assertFalse(route.isEnabled(candidate));
        assertThrows(NullPointerException.class, () -> route.isEnabled(null));
    }

    @Test
    void snapshotsRetainOrderedDetachedOutputAndProcessorLists() {
        List<String> outputs = new ArrayList<>(List.of("second", "first"));
        List<String> processors = new ArrayList<>(List.of("redact", "limit"));
        EffectiveRoute route = new EffectiveRoute("test", Level.INFO, outputs, processors, "root", true);
        outputs.clear();
        processors.clear();

        assertEquals(List.of("second", "first"), route.outputs());
        assertEquals(List.of("redact", "limit"), route.processors());
        assertThrows(UnsupportedOperationException.class, () -> route.outputs().clear());
        assertThrows(UnsupportedOperationException.class, () -> route.processors().clear());
    }
}
