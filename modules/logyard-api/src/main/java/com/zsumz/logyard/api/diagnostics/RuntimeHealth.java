package com.zsumz.logyard.api.diagnostics;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable point-in-time health report for one Logyard runtime. */
public record RuntimeHealth(
        Instant observedAt,
        HealthStatus status,
        boolean ready,
        List<ComponentHealth> components) {
    public static final int MAX_COMPONENTS = 1_024;

    public RuntimeHealth {
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(components, "components");
        if (components.size() > MAX_COMPONENTS) {
            throw new IllegalArgumentException(
                    "components exceeds " + MAX_COMPONENTS + " entries");
        }
        components = List.copyOf(components);
        if (ready && !status.ready()) {
            throw new IllegalArgumentException("a non-ready status cannot report ready=true");
        }
    }

    public static RuntimeHealth from(List<ComponentHealth> components) {
        List<ComponentHealth> snapshot = List.copyOf(components);
        HealthStatus aggregate = HealthStatus.HEALTHY;
        for (ComponentHealth component : snapshot) {
            aggregate = HealthStatus.worst(aggregate, component.status());
        }
        return new RuntimeHealth(Instant.now(), aggregate, aggregate.ready(), snapshot);
    }
}
