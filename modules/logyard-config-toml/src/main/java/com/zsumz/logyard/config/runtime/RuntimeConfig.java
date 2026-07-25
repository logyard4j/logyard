package com.zsumz.logyard.config.runtime;

import java.time.Duration;
import java.util.Objects;

/**
 * Installation-owned process lifecycle and file-watcher settings.
 *
 * <p>These values are selected during startup or an application/framework configuration handoff;
 * ordinary in-place reload rejects changes to them.</p>
 */
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
