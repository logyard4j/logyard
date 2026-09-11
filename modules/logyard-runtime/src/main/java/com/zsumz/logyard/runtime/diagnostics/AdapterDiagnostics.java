package com.zsumz.logyard.runtime.diagnostics;

import com.zsumz.logyard.api.failure.FailureIsolation;
import com.zsumz.logyard.api.diagnostics.DiagnosticRateLimiter;
import com.zsumz.logyard.core.diagnostics.EmergencyText;
import com.zsumz.logyard.core.diagnostics.EmergencyReporter;

import java.time.Duration;

/** Bounded, rate-limited diagnostics that cannot re-enter a logging façade. */
public final class AdapterDiagnostics {
    private static final DiagnosticRateLimiter REPORTS = new DiagnosticRateLimiter(Duration.ofSeconds(10L));

    private AdapterDiagnostics() {
    }

    public static void shutdownHookFailure(Throwable failure) {
        FailureIsolation.prepareForRecovery(failure);
        report("could not install adapter shutdown hook", failure, true);
    }

    public static void adapterFailure(String adapterName, String operation, Throwable failure) {
        FailureIsolation.prepareForRecovery(failure);
        report("adapter " + adapterName + " " + operation + " failed", failure, false);
    }

    /** Retained for callers compiled against the early adapter diagnostics surface. */
    public static void rethrowIfFatal(Throwable failure) {
        FailureIsolation.prepareForRecovery(failure);
    }

    /** Dispatches bounded internal text without writing to stderr on the caller thread. */
    public static void report(String message) {
        EmergencyReporter.STDERR.report(message);
    }

    private static void report(String stage, Throwable failure, boolean force) {
        if (!force && !REPORTS.tryAcquire()) {
            return;
        }
        long suppressed = REPORTS.drainSuppressed();
        StringBuilder text = new StringBuilder(256)
                .append("Logyard: ")
                .append(EmergencyText.sanitize(stage, 512))
                .append(": ")
                .append(EmergencyText.failureSummary(failure, 2_048));
        if (suppressed > 0L) {
            text.append(" (").append(suppressed).append(" similar diagnostic(s) suppressed)");
        }
        report(text.toString());
    }
}
