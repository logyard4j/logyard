package com.zsumz.logyard.output.json.flush;

import com.zsumz.logyard.api.failure.FailureIsolation;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, rate-limited diagnostics for failures escaping a timed-flush task. */
@FunctionalInterface
interface FlushDiagnostics {
    FlushDiagnostics STDERR = new StderrFlushDiagnostics();

    void report(Throwable failure);

    final class StderrFlushDiagnostics implements FlushDiagnostics {
        private static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10L);
        private static final int MAX_MESSAGE_CHARACTERS = 512;

        private final AtomicLong nextReportNanos = new AtomicLong();
        private final AtomicLong suppressed = new AtomicLong();

        @Override
        public void report(Throwable failure) {
            FailureIsolation.prepareForRecovery(failure);
            long now = System.nanoTime();
            long next = nextReportNanos.get();
            if (now < next || !nextReportNanos.compareAndSet(next, saturatedAdd(now, REPORT_INTERVAL_NANOS))) {
                suppressed.incrementAndGet();
                return;
            }
            long hidden = suppressed.getAndSet(0L);
            try {
                System.err.println(message(failure, hidden));
            } catch (Throwable reportingFailure) {
                FailureIsolation.prepareForRecovery(reportingFailure);
            }
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
