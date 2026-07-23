package com.zsumz.logyard.api.diagnostics;

import java.util.Objects;

/** Stable operational state shared by local and remote Logyard components. */
public enum HealthStatus {
    HEALTHY(0),
    STARTING(1),
    RECOVERING(1),
    DEGRADED(2),
    OPEN_CIRCUIT(3),
    STOPPING(3),
    STOPPED(4),
    FAILED(5);

    private final int severity;

    HealthStatus(int severity) {
        this.severity = severity;
    }

    /** Returns the more operationally severe of two states. */
    public static HealthStatus worst(HealthStatus left, HealthStatus right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return left.severity >= right.severity ? left : right;
    }

    /** Whether the component can still accept ordinary event traffic. */
    public boolean ready() {
        return this == HEALTHY || this == RECOVERING || this == DEGRADED;
    }
}
