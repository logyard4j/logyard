package com.zsumz.logyard.slf4j.internal.diagnostics;

import com.zsumz.logyard.api.failure.FailureIsolation;

import java.io.PrintStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Emergency diagnostics that never route back through SLF4J or Logyard. */
public final class ProviderDiagnostics {
    private static final int MAX_MESSAGE_CHARACTERS = 512;
    private static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final AtomicLong NEXT_REPORT_NANOS = new AtomicLong();
    private static final AtomicLong SUPPRESSED = new AtomicLong();

    private ProviderDiagnostics() {
    }

    public static void initializationFailure(Throwable failure) {
        FailureIsolation.prepareForRecovery(failure);
        write(System.err, "provider initialization failed", failure, true);
    }

    public static void shutdownHookFailure(Throwable failure) {
        FailureIsolation.prepareForRecovery(failure);
        write(System.err, "could not install shutdown hook", failure, true);
    }

    public static void eventMappingFailure(String loggerName, Throwable failure) {
        FailureIsolation.prepareForRecovery(failure);
        String stage = "event mapping failed for logger " + sanitize(loggerName);
        write(System.err, stage, failure, false);
    }

    public static void captureFailure(String loggerName, Throwable failure) {
        FailureIsolation.prepareForRecovery(failure);
        String stage = "event metadata was partially unavailable for logger " + sanitize(loggerName);
        write(System.err, stage, failure, false);
    }

    /** Retained for internal façade call sites that classify before reporting. */
    public static void rethrowIfFatal(Throwable failure) {
        FailureIsolation.prepareForRecovery(failure);
    }

    private static void write(PrintStream stream, String stage, Throwable failure, boolean force) {
        if (!force && !acquireReportPermit()) {
            SUPPRESSED.incrementAndGet();
            return;
        }
        long suppressed = SUPPRESSED.getAndSet(0L);
        StringBuilder message = new StringBuilder(192)
                .append("Logyard SLF4J provider: ")
                .append(sanitize(stage))
                .append(" [")
                .append(failure.getClass().getName())
                .append(']');
        String detail = safeMessage(failure);
        if (detail != null && !detail.isBlank()) {
            message.append(": ").append(sanitize(detail));
        }
        if (suppressed > 0L) {
            message.append(" (").append(suppressed).append(" similar diagnostic(s) suppressed)");
        }
        stream.println(bound(message.toString()));
    }

    private static boolean acquireReportPermit() {
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

    private static String safeMessage(Throwable failure) {
        try {
            return failure.getMessage();
        } catch (Throwable messageFailure) {
            FailureIsolation.prepareForRecovery(messageFailure);
            return "<message unavailable>";
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "<unknown>";
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_MESSAGE_CHARACTERS));
        int retained = Math.min(value.length(), MAX_MESSAGE_CHARACTERS);
        for (int index = 0; index < retained; index++) {
            char current = value.charAt(index);
            if (current < 0x20 || current == 0x7f) {
                result.append(String.format("\\u%04x", (int) current));
            } else {
                result.append(current);
            }
        }
        if (value.length() > retained) {
            result.append("...");
        }
        return result.toString();
    }

    private static String bound(String value) {
        return value.length() <= MAX_MESSAGE_CHARACTERS
                ? value
                : value.substring(0, MAX_MESSAGE_CHARACTERS - 3) + "...";
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        return result < left ? Long.MAX_VALUE : result;
    }
}
