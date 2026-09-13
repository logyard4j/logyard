package com.logyard4j.logyard.core.delivery.async;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Builds bounded internal events from accumulated asynchronous-delivery drops. */
final class AsyncDropReporter {
    private static final long REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final AsyncSinkMetrics metrics;
    private long nextReportNanos = System.nanoTime() + REPORT_INTERVAL_NANOS;

    AsyncDropReporter(AsyncSinkMetrics metrics) {
        this.metrics = metrics;
    }

    LogEvent nextReport(boolean force) {
        long now = System.nanoTime();
        if (!force && now < nextReportNanos) {
            return null;
        }
        nextReportNanos = now + REPORT_INTERVAL_NANOS;

        AttributeSet.Builder attributes = AttributeSet.systemBuilder();
        long total = 0L;
        for (Map.Entry<Level, Long> dropped : metrics.drainPendingDropReport().entrySet()) {
            attributes.put("logyard.dropped." + dropped.getKey().name().toLowerCase(java.util.Locale.ROOT), dropped.getValue());
            total += dropped.getValue();
        }
        if (total == 0L) {
            return null;
        }

        Thread thread = Thread.currentThread();
        long timestampMillis = System.currentTimeMillis();
        return new LogEvent(
                timestampMillis,
                TimeUnit.MILLISECONDS.toNanos(timestampMillis),
                Level.WARN,
                "logyard.internal.async",
                "logyard.async.events_dropped",
                "Dropped {} log events because an output was full or closing",
                new Object[] {total},
                attributes.put("logyard.dropped.total", total).build(),
                null,
                thread.threadId(),
                thread.getName());
    }
}
