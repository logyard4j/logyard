package com.logyard4j.logyard.runtime.installation;

import com.logyard4j.logyard.runtime.diagnostics.ReloadDiagnostics;

import java.time.Duration;
import java.util.Objects;

/** Immutable watcher behavior selected from the same snapshot as the runtime configuration. */
record ConfigurationWatcherPolicy(
        Duration debounce,
        Duration closeTimeout,
        ReloadDiagnostics diagnostics) {
    ConfigurationWatcherPolicy {
        Objects.requireNonNull(debounce, "debounce");
        Objects.requireNonNull(closeTimeout, "closeTimeout");
        Objects.requireNonNull(diagnostics, "diagnostics");
    }
}
