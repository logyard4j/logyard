package com.logyard4j.runtime.diagnostics;

import java.nio.file.Path;

/** Lifecycle diagnostics that never re-enter the logging runtime. */
public interface ReloadDiagnostics {
    default void unchanged(Path source, String digest) {
    }

    default void unchanged(String sourceDescription, String digest) {
    }

    default void applied(Path source, String previousDigest, String nextDigest) {
    }

    default void applied(String sourceDescription, String previousDigest, String nextDigest) {
    }

    default void rejected(Path source, Throwable failure) {
    }

    default void rejected(String sourceDescription, Throwable failure) {
    }

    default void watcherStopped(Path source, Throwable failure) {
    }

    static ReloadDiagnostics silent() {
        return new ReloadDiagnostics() { };
    }
}
