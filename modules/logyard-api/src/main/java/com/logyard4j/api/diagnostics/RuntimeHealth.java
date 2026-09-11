package com.logyard4j.api.diagnostics;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable point-in-time health report for one Logyard runtime.
 *
 * @param observedAt time at which the snapshot was assembled
 * @param status worst component status
 * @param ready whether the runtime can accept ordinary event traffic
 * @param components component snapshots contributing to the aggregate
 */
public record RuntimeHealth(
        Instant observedAt,
        HealthStatus status,
        boolean ready,
        List<ComponentHealth> components) {
    /** Maximum number of component snapshots in one report. */
    public static final int MAX_COMPONENTS = 1_024;

    /** Validates consistency and detaches the report from caller-owned collections. */
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

    /**
     * Aggregates current component snapshots into a runtime report.
     *
     * @param components component snapshots
     * @return report observed at the current instant
     */
    public static RuntimeHealth from(List<ComponentHealth> components) {
        List<ComponentHealth> snapshot = List.copyOf(components);
        HealthStatus aggregate = HealthStatus.HEALTHY;
        for (ComponentHealth component : snapshot) {
            aggregate = HealthStatus.worst(aggregate, component.status());
        }
        return new RuntimeHealth(Instant.now(), aggregate, aggregate.ready(), snapshot);
    }
}
