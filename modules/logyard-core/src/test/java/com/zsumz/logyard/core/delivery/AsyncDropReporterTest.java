package com.zsumz.logyard.core.delivery;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.LogEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class AsyncDropReporterTest {
    @Test
    void createsOneBoundedReportFromPendingDropCounts() {
        AsyncSinkMetrics metrics = new AsyncSinkMetrics();
        metrics.recordDrop(Level.DEBUG);
        metrics.recordDrop(Level.DEBUG);
        metrics.recordDrop(Level.ERROR);
        AsyncDropReporter reporter = new AsyncDropReporter(metrics);

        LogEvent report = reporter.nextReport(true);

        assertEquals(Level.WARN, report.level());
        assertEquals("logyard.internal.async", report.loggerName());
        assertEquals("logyard.async.events_dropped", report.eventName());
        assertEquals(2L, report.attributes().get("logyard.dropped.debug"));
        assertEquals(1L, report.attributes().get("logyard.dropped.error"));
        assertEquals(3L, report.attributes().get("logyard.dropped.total"));
        assertNull(reporter.nextReport(true));
        assertEquals(3L, metrics.snapshot().dropped());
    }
}
