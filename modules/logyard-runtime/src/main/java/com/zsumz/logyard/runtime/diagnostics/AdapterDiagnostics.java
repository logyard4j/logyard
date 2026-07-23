package com.zsumz.logyard.runtime.diagnostics;

import com.zsumz.logyard.api.failure.FailureIsolation;
import com.zsumz.logyard.core.diagnostics.EmergencyText;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, rate-limited diagnostics that cannot re-enter a logging façade. */
public final class AdapterDiagnostics {
    private static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final AtomicLong NEXT_REPORT_NANOS = new AtomicLong();
    private static final AtomicLong SUPPRESSED = new AtomicLong();

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

    private static void report(String stage, Throwable failure, boolean force) {
        if (!force && !permit()) {
            SUPPRESSED.incrementAndGet();
            return;
        }
        long suppressed = SUPPRESSED.getAndSet(0L);
        StringBuilder text = new StringBuilder(256)
                .append("Logyard: ")
                .append(EmergencyText.sanitize(stage, 512))
                .append(": ")
                .append(EmergencyText.failureSummary(failure, 2_048));
        if (suppressed > 0L) {
            text.append(" (").append(suppressed).append(" similar diagnostic(s) suppressed)");
        }
        System.err.println(EmergencyText.sanitize(text.toString(), 4_096));
    }

    private static boolean permit() {
        long now = System.nanoTime();
        while (true) {
            long next = NEXT_REPORT_NANOS.get();
            if (now < next) {
                return false;
            }
            long replacement = saturatedAdd(now, REPORT_INTERVAL_NANOS);
            if (NEXT_REPORT_NANOS.compareAndSet(next, replacement)) {
                return true;
            }
        }
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        return result < left ? Long.MAX_VALUE : result;
    }
}
