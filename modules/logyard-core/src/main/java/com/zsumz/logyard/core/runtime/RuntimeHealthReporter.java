package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.diagnostics.HealthStatus;
import com.zsumz.logyard.api.diagnostics.RuntimeHealth;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.api.spi.HealthContributor;
import com.zsumz.logyard.core.routing.PlanEpoch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders bounded runtime and output health snapshots from an acquired plan epoch. */
final class RuntimeHealthReporter {
    private RuntimeHealthReporter() {
    }

    static RuntimeHealth running(int loggerCount, int pendingRetirements, RuntimePlan plan, PlanEpoch epoch) {
        List<ComponentHealth> components = new ArrayList<>();
        Map<String, String> runtimeDetails = new LinkedHashMap<>();
        runtimeDetails.put("closed", "false");
        runtimeDetails.put("plan_retiring", Boolean.toString(epoch.retiring()));

        Map<String, Long> runtimeMetrics = new LinkedHashMap<>();
        runtimeMetrics.put("logger_count", (long) loggerCount);
        runtimeMetrics.put("output_count", (long) plan.outputs().size());
        runtimeMetrics.put("pending_retirements", (long) pendingRetirements);
        components.add(new ComponentHealth(
                "runtime",
                "runtime",
                epoch.retiring() ? HealthStatus.DEGRADED : HealthStatus.HEALTHY,
                runtimeDetails,
                runtimeMetrics));

        plan.outputs().forEach((name, sink) -> components.add(output(name, sink)));
        return RuntimeHealth.from(components);
    }

    static RuntimeHealth stopped(int loggerCount) {
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
            try {
                return contributor.health(name);
            } catch (RuntimeException failure) {
                return new ComponentHealth(
                        name,
                        "output",
                        HealthStatus.FAILED,
                        Map.of("health_failure", failure.getClass().getName()),
                        Map.of());
            }
        }
        return ComponentHealth.healthy(name, "output");
    }
}
