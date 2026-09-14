package com.logyard4j.logyard.core.runtime.management;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.diagnostics.ComponentHealth;
import com.logyard4j.logyard.api.diagnostics.HealthStatus;
import com.logyard4j.logyard.api.diagnostics.RuntimeHealth;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.diagnostics.HealthContributor;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.routing.RouteDefinition;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.logyard.core.runtime.RuntimePlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class RuntimeHealthReporterTest {
    @Test
    void anUnavailableOutputKeepsTheRuntimeUnreadyBesideADegradedOutput() {
        try (DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("starting", "degraded"), List.of()),
                Map.of(),
                Map.of("starting", new ReadinessSink(HealthStatus.STARTING),
                        "degraded", new ReadinessSink(HealthStatus.DEGRADED)),
                Map.of()))) {
            RuntimeHealth health = runtime.health();
            assertEquals(HealthStatus.STARTING, health.status());
            assertFalse(health.ready());
        }
    }

    @Test
    void isolatesRecoverableHealthContributorErrorsWithoutDisablingDelivery() {
        HostileHealthSink sink = new HostileHealthSink();
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("hostile"), List.of()),
                Map.of(),
                Map.of("hostile", sink),
                Map.of()));
        try {
            RuntimeHealth health = runtime.health();
            runtime.logger("test").info("still delivered");

            ComponentHealth output = health.components().stream()
                    .filter(component -> component.name().equals("hostile"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(HealthStatus.FAILED, output.status());
            assertEquals(1, sink.delivered.get());
        } finally {
            runtime.close();
        }
    }

    private static final class HostileHealthSink implements EventSink, HealthContributor {
        private final AtomicInteger delivered = new AtomicInteger();

        @Override
        public void accept(LogEvent event) {
            delivered.incrementAndGet();
        }

        @Override
        public ComponentHealth health(String componentName) {
            throw new AssertionError("health failed");
        }
    }

    private record ReadinessSink(HealthStatus status) implements EventSink, HealthContributor {
        @Override
        public void accept(LogEvent event) {
        }

        @Override
        public ComponentHealth health(String componentName) {
            return new ComponentHealth(componentName, "output", status, Map.of(), Map.of());
        }
    }
}
