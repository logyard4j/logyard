package com.logyard4j.runtime.reload.coordination;

import com.logyard4j.core.failure.ComponentInvocationBoundary;
import com.logyard4j.runtime.diagnostics.ReloadDiagnostics;

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

    void unchanged(String sourceDescription, String digest) {
        notify(() -> diagnostics.unchanged(sourceDescription, digest));
    }

    void applied(Path source, String previousDigest, String nextDigest) {
        notify(() -> diagnostics.applied(source, previousDigest, nextDigest));
    }

    void applied(String sourceDescription, String previousDigest, String nextDigest) {
        notify(() -> diagnostics.applied(sourceDescription, previousDigest, nextDigest));
    }

    void rejected(Path source, Throwable failure) {
        notify(() -> diagnostics.rejected(source, failure));
    }

    void rejected(String sourceDescription, Throwable failure) {
        notify(() -> diagnostics.rejected(sourceDescription, failure));
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
