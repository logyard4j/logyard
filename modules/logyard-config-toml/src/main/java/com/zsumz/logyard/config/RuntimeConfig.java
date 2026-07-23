package com.zsumz.logyard.config;

import java.time.Duration;
import java.util.Objects;

/** Process-lifecycle and live-reload settings. */
public record RuntimeConfig(
        Duration shutdownTimeout,
        String internalStatus,
        boolean watch,
        Duration reloadDebounce) {
    public RuntimeConfig {
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        Objects.requireNonNull(internalStatus, "internalStatus");
        Objects.requireNonNull(reloadDebounce, "reloadDebounce");
        if (shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownTimeout must not be negative");
        }
        if (reloadDebounce.isNegative() || reloadDebounce.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("reloadDebounce must be between 0s and 30s");
        }
    }
}
