package com.logyard4j.logyard.core.diagnostics;

import com.logyard4j.logyard.api.failure.FailureIsolation;

import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Best-effort internal diagnostics with one bounded in-flight message and no caller-thread I/O. */
public final class EmergencyReporter {
    public static final EmergencyReporter STDERR = new EmergencyReporter(Duration.ofSeconds(10));

    private final long intervalNanos;
    private final AtomicReference<ReporterPhase> phase = new AtomicReference<>(ReporterPhase.NEW);
    private final AtomicLong suppressed = new AtomicLong();
    private final AtomicLong pendingSuppressed = new AtomicLong();
    private long previousReportNanos;

    EmergencyReporter(Duration interval) {
        intervalNanos = interval.toNanos();
    }

    /** Reports at most once per interval; a stalled stderr retains at most one daemon and message. */
    public void report(String message) {
        ReporterPhase previous = phase.getAndSet(ReporterPhase.RUNNING);
        if (previous == ReporterPhase.RUNNING) {
            suppress();
            return;
        }
        long now = System.nanoTime();
        if (previous == ReporterPhase.AVAILABLE && now - previousReportNanos < intervalNanos) {
            suppress();
            phase.set(ReporterPhase.AVAILABLE);
            return;
        }
        previousReportNanos = now;
        try {
            String bounded = EmergencyText.sanitize(message, 4_096);
            PrintStream destination = System.err;
            Thread reporter = new Thread(null, () -> write(destination, bounded),
                    "logyard-emergency-diagnostic", 0L, false);
            reporter.setDaemon(true);
            reporter.setContextClassLoader(null);
            reporter.start();
        } catch (Throwable failure) {
            phase.set(ReporterPhase.AVAILABLE);
            suppress();
            FailureIsolation.prepareForRecovery(failure);
        }
    }

    /** Returns the cumulative number of diagnostics suppressed or lost by this reporter. */
    public long suppressedReports() {
        return suppressed.get();
    }

    private void write(PrintStream destination, String message) {
        try {
            long hidden = pendingSuppressed.getAndSet(0);
            destination.println(hidden == 0 ? message : message + " (" + hidden + " diagnostic(s) suppressed)");
            if (destination.checkError()) {
                suppress();
            }
        } catch (Throwable failure) {
            suppress();
            FailureIsolation.prepareForRecovery(failure);
        } finally {
            phase.set(ReporterPhase.AVAILABLE);
        }
    }

    private void suppress() {
        suppressed.incrementAndGet();
        pendingSuppressed.incrementAndGet();
    }

    private enum ReporterPhase {
        NEW,
        AVAILABLE,
        RUNNING
    }
}
