package com.zsumz.logyard.core.delivery;

import com.zsumz.logyard.api.Level;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AsyncSinkMetricsTest {
    @Test
    void snapshotsCountersAndDrainsOnlyPendingDropReports() {
        AsyncSinkMetrics metrics = new AsyncSinkMetrics();

        metrics.recordEnqueued();
        metrics.recordDelivered();
        metrics.recordDelivered(2);
        metrics.recordDrop(Level.DEBUG);
        metrics.recordDrop(Level.DEBUG);
        metrics.recordDrop(Level.ERROR);
        metrics.recordSynchronousFallback();
        metrics.recordEmergencyFallback();
        metrics.recordEmergencyFallbacks(2);

        assertEquals(new AsyncSinkMetrics.Snapshot(1, 3, 3, 1, 3), metrics.snapshot());
        assertEquals(Map.of(Level.DEBUG, 2L, Level.ERROR, 1L), metrics.drainPendingDropReport());
        assertEquals(Map.of(), metrics.drainPendingDropReport());
        assertEquals(2L, metrics.dropped(Level.DEBUG));
        assertEquals(3L, metrics.snapshot().dropped());
    }
}
