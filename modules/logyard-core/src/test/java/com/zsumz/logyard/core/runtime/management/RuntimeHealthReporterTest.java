package com.zsumz.logyard.core.runtime.management;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.diagnostics.HealthStatus;
import com.zsumz.logyard.api.diagnostics.RuntimeHealth;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.diagnostics.HealthContributor;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.routing.RouteDefinition;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.core.runtime.RuntimePlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RuntimeHealthReporterTest {
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
}
