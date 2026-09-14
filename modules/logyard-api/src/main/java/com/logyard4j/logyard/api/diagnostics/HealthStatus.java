package com.logyard4j.logyard.api.diagnostics;

import java.util.Objects;

/**
 * Stable operational state shared by local and remote Logyard components.
 *
 * <p>Aggregation ranks every unavailable state above every ready state. From least to most
 * severe: healthy, recovering, degraded, starting, open circuit, stopping, stopped, failed.
 * This ordering is independent of declaration order and produces the same result in either direction.</p>
 */
public enum HealthStatus {
    /** Fully operational. */
    HEALTHY(0),

    /** Starting and not yet ready. */
    STARTING(3),

    /** Restoring normal operation while remaining ready. */
    RECOVERING(1),

    /** Ready with impaired behavior or reduced capacity. */
    DEGRADED(2),

    /** Temporarily refusing work after repeated failures. */
    OPEN_CIRCUIT(4),

    /** Shutting down and no longer accepting ordinary work. */
    STOPPING(5),

    /** Completely stopped. */
    STOPPED(6),

    /** Unable to operate. */
    FAILED(7);

    private final int severity;

    HealthStatus(int severity) {
        this.severity = severity;
    }

    /**
     * Returns the more operationally severe of two states.
     *
     * @param left first state
     * @param right second state
     * @return the state with greater operational severity
     */
    public static HealthStatus worst(HealthStatus left, HealthStatus right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return left.severity >= right.severity ? left : right;
    }

    /**
     * Returns whether the component can still accept ordinary event traffic.
     *
     * @return {@code true} for healthy, recovering, and degraded states
     */
    public boolean ready() {
        return this == HEALTHY || this == RECOVERING || this == DEGRADED;
    }
}
