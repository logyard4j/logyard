package com.logyard4j.logyard.core.runtime.management;

import com.logyard4j.logyard.api.diagnostics.ComponentHealth;
import com.logyard4j.logyard.api.diagnostics.HealthStatus;
import com.logyard4j.logyard.api.diagnostics.RuntimeHealth;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.api.spi.diagnostics.HealthContributor;
import com.logyard4j.logyard.core.failure.ComponentInvocationBoundary;
import com.logyard4j.logyard.core.diagnostics.EmergencyReporter;
import com.logyard4j.logyard.core.routing.PlanEpoch;
import com.logyard4j.logyard.core.runtime.RuntimePlan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Renders bounded snapshots of active plans and runtime lifecycle state. */
public final class RuntimeHealthReporter {
    private RuntimeHealthReporter() {
    }

    public static RuntimeHealth running(int loggerCount, int pendingRetirements, RuntimePlan plan, PlanEpoch epoch) {
        List<ComponentHealth> components = new ArrayList<>();
        Map<String, String> runtimeDetails = new LinkedHashMap<>();
        runtimeDetails.put("closed", "false");
        runtimeDetails.put("plan_retiring", Boolean.toString(epoch.retiring()));

        Map<String, Long> runtimeMetrics = new LinkedHashMap<>();
        runtimeMetrics.put("logger_count", (long) loggerCount);
        runtimeMetrics.put("output_count", (long) plan.outputs().size());
        runtimeMetrics.put("pending_retirements", (long) pendingRetirements);
        runtimeMetrics.put("diagnostics_suppressed_total", EmergencyReporter.STDERR.suppressedReports());
        components.add(new ComponentHealth(
                "runtime",
                "runtime",
                epoch.retiring() ? HealthStatus.DEGRADED : HealthStatus.HEALTHY,
                runtimeDetails,
                runtimeMetrics));

        plan.outputs().forEach((name, sink) -> components.add(output(name, sink)));
        return RuntimeHealth.from(components);
    }

    public static RuntimeHealth stopping(int loggerCount, int pendingRetirements) {
        return new RuntimeHealth(
                Instant.now(),
                HealthStatus.STOPPING,
                false,
                List.of(new ComponentHealth(
                        "runtime",
                        "runtime",
                        HealthStatus.STOPPING,
                        Map.of("closed", "true"),
                        Map.of("logger_count", (long) loggerCount, "pending_retirements", (long) pendingRetirements))));
    }

    public static RuntimeHealth stopped(int loggerCount) {
        return new RuntimeHealth(
                Instant.now(),
                HealthStatus.STOPPED,
                false,
                List.of(new ComponentHealth(
                        "runtime",
                        "runtime",
                        HealthStatus.STOPPED,
                        Map.of("closed", "true"),
                        Map.of("logger_count", (long) loggerCount))));
    }

    private static ComponentHealth output(String name, EventSink sink) {
        if (sink instanceof HealthContributor contributor) {
            AtomicReference<ComponentHealth> result = new AtomicReference<>();
            AtomicReference<Throwable> healthFailure = new AtomicReference<>();
            if (ComponentInvocationBoundary.invoke(
                    "output '" + name + "' health contributor",
                    () -> result.set(Objects.requireNonNull(
                            contributor.health(name),
                            "health contributor returned null")),
                    (component, failure) -> healthFailure.set(failure))) {
                return result.get();
            }
            return new ComponentHealth(
                    name,
                    "output",
                    HealthStatus.FAILED,
                    Map.of("health_failure", healthFailure.get().getClass().getName()),
                    Map.of());
        }
        return ComponentHealth.healthy(name, "output");
    }
}
