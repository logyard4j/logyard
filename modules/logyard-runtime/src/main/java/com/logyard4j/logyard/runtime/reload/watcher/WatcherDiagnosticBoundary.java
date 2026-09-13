package com.logyard4j.logyard.runtime.reload.watcher;

import com.logyard4j.logyard.core.failure.ComponentInvocationBoundary;
import com.logyard4j.logyard.runtime.diagnostics.ReloadDiagnostics;

import java.nio.file.Path;
import java.util.Objects;

/** Contains observer failures so they cannot terminate the filesystem watch loop. */
final class WatcherDiagnosticBoundary {
    private final ReloadDiagnostics diagnostics;

    WatcherDiagnosticBoundary(ReloadDiagnostics diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    void rejected(Path source, Throwable failure) {
        notify(() -> diagnostics.rejected(source, failure));
    }

    void watcherStopped(Path source, Throwable failure) {
        notify(() -> diagnostics.watcherStopped(source, failure));
    }

    private static void notify(Runnable notification) {
        ComponentInvocationBoundary.invoke(
                "reload diagnostics",
                notification::run,
                (component, failure) -> {
                });
    }
}
