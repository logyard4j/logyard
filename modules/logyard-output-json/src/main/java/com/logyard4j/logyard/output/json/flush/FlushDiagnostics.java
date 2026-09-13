package com.logyard4j.logyard.output.json.flush;

import com.logyard4j.logyard.api.failure.FailureIsolation;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded, rate-limited diagnostics for failures escaping a timed-flush task. */
@FunctionalInterface
interface FlushDiagnostics {
    FlushDiagnostics STDERR = new StderrFlushDiagnostics();

    void report(Throwable failure);

    final class StderrFlushDiagnostics implements FlushDiagnostics {
        private static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10L);
        private static final int MAX_MESSAGE_CHARACTERS = 512;
        private static final String REPORTER_NAME = "logyard-json-flush-diagnostic";

        private final long reportIntervalNanos;
        private final AtomicLong nextReportNanos = new AtomicLong();
        private final AtomicLong suppressed = new AtomicLong();
        private final AtomicReference<ReporterPhase> reporterPhase = new AtomicReference<>(ReporterPhase.AVAILABLE);

        StderrFlushDiagnostics() {
            this(REPORT_INTERVAL_NANOS);
        }

        StderrFlushDiagnostics(long reportIntervalNanos) {
            this.reportIntervalNanos = reportIntervalNanos;
        }

        @Override
        public void report(Throwable failure) {
            long now = System.nanoTime();
            long next = nextReportNanos.get();
            if (!reserveReport(now, next)) {
                suppressed.incrementAndGet();
                return;
            }
            try {
                Thread reporter = new Thread(null, () -> write(failure), REPORTER_NAME, 0L, false);
                reporter.setDaemon(true);
                reporter.setContextClassLoader(null);
                reporter.start();
            } catch (Throwable startFailure) {
                releaseReporter();
                suppressed.incrementAndGet();
                FailureIsolation.prepareForRecovery(startFailure);
            }
        }

        private boolean reserveReport(long now, long next) {
            if (now < next || !reporterPhase.compareAndSet(ReporterPhase.AVAILABLE, ReporterPhase.RUNNING)) {
                return false;
            }
            if (nextReportNanos.compareAndSet(next, saturatedAdd(now, reportIntervalNanos))) {
                return true;
            }
            releaseReporter();
            return false;
        }

        long suppressedReports() {
            return suppressed.get();
        }

        boolean reportIsInFlight() {
            return reporterPhase.get() == ReporterPhase.RUNNING;
        }

        private void write(Throwable failure) {
            try {
                FailureIsolation.prepareForRecovery(failure);
                System.err.println(message(failure, suppressed.getAndSet(0L)));
            } catch (Throwable reportingFailure) {
                FailureIsolation.prepareForRecovery(reportingFailure);
            } finally {
                releaseReporter();
            }
        }

        private void releaseReporter() {
            reporterPhase.set(ReporterPhase.AVAILABLE);
        }

        private enum ReporterPhase {
            AVAILABLE,
            RUNNING
        }

        private static String message(Throwable failure, long hidden) {
            String detail = failure.getMessage();
            StringBuilder message = new StringBuilder(128)
                    .append("Logyard timed JSON flush failed: ")
                    .append(failure.getClass().getName());
            if (detail != null && !detail.isBlank()) {
                message.append(": ").append(sanitize(detail));
            }
            if (hidden > 0L) {
                message.append(" (").append(hidden).append(" similar failure(s) suppressed)");
            }
            return message.toString();
        }

        private static String sanitize(String value) {
            StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_MESSAGE_CHARACTERS));
            for (int index = 0; index < value.length() && result.length() < MAX_MESSAGE_CHARACTERS; index++) {
                char character = value.charAt(index);
                result.append(Character.isISOControl(character) ? ' ' : character);
            }
            return result.toString();
        }

        private static long saturatedAdd(long left, long right) {
            long result = left + right;
            return result < left ? Long.MAX_VALUE : result;
        }
    }
}
