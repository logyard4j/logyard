package com.logyard4j.logyard.runtime.diagnostics;

import com.logyard4j.logyard.core.diagnostics.EmergencyText;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;

/** Rate-small, bounded fallback diagnostics written directly to a caller-selected stream. */
public final class StderrReloadDiagnostics implements ReloadDiagnostics {
    private static final int MAX_DESCRIPTION_CHARS = 2_048;
    private static final int MAX_FAILURE_CHARS = 4_096;

    private final PrintStream stream;

    public StderrReloadDiagnostics(PrintStream stream) {
        this.stream = Objects.requireNonNull(stream, "stream");
    }

    @Override
    public void applied(Path source, String previousDigest, String nextDigest) {
        applied(String.valueOf(source), previousDigest, nextDigest);
    }

    @Override
    public void applied(String sourceDescription, String previousDigest, String nextDigest) {
        stream.println("Logyard reloaded " + safeDescription(sourceDescription) + " " + shortDigest(previousDigest) + " -> " + shortDigest(nextDigest));
    }

    @Override
    public void rejected(Path source, Throwable failure) {
        rejected(String.valueOf(source), failure);
    }

    @Override
    public void rejected(String sourceDescription, Throwable failure) {
        stream.println("Logyard kept the previous configuration after rejecting " + safeDescription(sourceDescription) + ": "
                + EmergencyText.failureSummary(failure, MAX_FAILURE_CHARS));
    }

    @Override
    public void watcherStopped(Path source, Throwable failure) {
        stream.println("Logyard configuration watcher stopped for " + safeDescription(String.valueOf(source)) + ": "
                + EmergencyText.failureSummary(failure, MAX_FAILURE_CHARS));
    }

    private static String safeDescription(String sourceDescription) {
        return EmergencyText.sanitize(String.valueOf(sourceDescription), MAX_DESCRIPTION_CHARS);
    }

    private static String shortDigest(String digest) {
        if (digest == null) {
            return "none";
        }
        return digest.substring(0, Math.min(12, digest.length()));
    }
}
