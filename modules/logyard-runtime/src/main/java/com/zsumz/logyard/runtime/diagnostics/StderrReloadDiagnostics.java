package com.zsumz.logyard.runtime.diagnostics;

import com.zsumz.logyard.core.diagnostics.EmergencyText;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;

/** Rate-small, bounded fallback diagnostics written directly to a caller-selected stream. */
public final class StderrReloadDiagnostics implements ReloadDiagnostics {
    private static final int MAX_PATH_CHARS = 2_048;
    private static final int MAX_FAILURE_CHARS = 4_096;

    private final PrintStream stream;

    public StderrReloadDiagnostics(PrintStream stream) {
        this.stream = Objects.requireNonNull(stream, "stream");
    }

    @Override
    public void applied(Path source, String previousDigest, String nextDigest) {
        stream.println("Logyard reloaded " + safePath(source) + " "
                + shortDigest(previousDigest) + " -> " + shortDigest(nextDigest));
    }

    @Override
    public void rejected(Path source, Throwable failure) {
        stream.println("Logyard kept the previous configuration after rejecting " + safePath(source)
                + ": " + EmergencyText.failureSummary(failure, MAX_FAILURE_CHARS));
    }

    @Override
    public void watcherStopped(Path source, Throwable failure) {
        stream.println("Logyard configuration watcher stopped for " + safePath(source)
                + ": " + EmergencyText.failureSummary(failure, MAX_FAILURE_CHARS));
    }

    private static String safePath(Path source) {
        return EmergencyText.sanitize(String.valueOf(source), MAX_PATH_CHARS);
    }

    private static String shortDigest(String digest) {
        if (digest == null) {
            return "none";
        }
        return digest.substring(0, Math.min(12, digest.length()));
    }
}
