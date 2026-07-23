package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.core.failure.ComponentInvocationBoundary;
import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;

import java.nio.file.Path;
import java.util.Objects;

/** Prevents observer failures from changing an already-determined reload result. */
final class ReloadDiagnosticBoundary {
    private final ReloadDiagnostics diagnostics;

    ReloadDiagnosticBoundary(ReloadDiagnostics diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    void unchanged(Path source, String digest) {
        notify(() -> diagnostics.unchanged(source, digest));
    }

    void applied(Path source, String previousDigest, String nextDigest) {
        notify(() -> diagnostics.applied(source, previousDigest, nextDigest));
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
