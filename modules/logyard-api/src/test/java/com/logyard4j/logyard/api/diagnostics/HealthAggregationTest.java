package com.logyard4j.logyard.api.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HealthAggregationTest {
    @Test
    void startingOutputCannotBeMaskedByAnAvailableDegradedOutput() {
        ComponentHealth starting = component("starting", HealthStatus.STARTING);
        ComponentHealth degraded = component("degraded", HealthStatus.DEGRADED);

        for (List<ComponentHealth> order : List.of(List.of(starting, degraded), List.of(degraded, starting))) {
            RuntimeHealth health = RuntimeHealth.from(order);
            assertEquals(HealthStatus.STARTING, health.status());
            assertFalse(health.ready());
        }
    }

    @Test
    void aggregateReadinessRequiresEveryComponentToBeReady() {
        for (HealthStatus left : HealthStatus.values()) {
            for (HealthStatus right : HealthStatus.values()) {
                RuntimeHealth health = RuntimeHealth.from(List.of(component("left", left), component("right", right)));
                assertEquals(left.ready() && right.ready(), health.ready(), left + " / " + right);
            }
        }
    }

    @Test
    void severityIsDeterministicRegardlessOfComponentOrder() {
        List<HealthStatus> severity = List.of(
                HealthStatus.HEALTHY, HealthStatus.RECOVERING, HealthStatus.DEGRADED, HealthStatus.STARTING,
                HealthStatus.OPEN_CIRCUIT, HealthStatus.STOPPING, HealthStatus.STOPPED, HealthStatus.FAILED);
        for (int left = 0; left < severity.size(); left++) {
            for (int right = 0; right < severity.size(); right++) {
                assertEquals(severity.get(Math.max(left, right)),
                        HealthStatus.worst(severity.get(left), severity.get(right)));
            }
        }
    }

    @Test
    void emptyAndAvailableComponentSetsRemainReady() {
        assertTrue(RuntimeHealth.from(List.of()).ready());
        RuntimeHealth health = RuntimeHealth.from(List.of(
                component("recovering", HealthStatus.RECOVERING), component("degraded", HealthStatus.DEGRADED)));
        assertTrue(health.ready());
        assertEquals(HealthStatus.DEGRADED, health.status());
    }

    private static ComponentHealth component(String name, HealthStatus status) {
        return new ComponentHealth(name, "output", status, Map.of(), Map.of());
    }
}
