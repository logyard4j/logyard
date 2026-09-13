package com.logyard4j.logyard.core.delivery.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.output.BatchEventSink;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AsyncBatchDeliveryTest {
    @Test
    void deliversImmediatelyAvailableFollowersAndCompletesEveryClaim() {
        AsyncEventQueue queue = new AsyncEventQueue(16);
        RecordingBatchSink delegate = new RecordingBatchSink();
        AsyncBatchDelivery delivery = delivery(queue, delegate, 4, 0L);
        LogEvent first = event(1);
        LogEvent second = event(2);
        LogEvent third = event(3);
        queue.offerImmediately(first, () -> true);
        queue.offerImmediately(second, () -> true);
        queue.offerImmediately(third, () -> true);

        assertFalse(delivery.deliver(queue.claimNow(), ignored -> {
            throw new AssertionError("zero-delay batch collection must not wait");
        }));

        assertEquals(List.of(first, second, third), delegate.batch());
        assertEquals(0, queue.outstanding());
    }

    @Test
    void deliversTheClaimedEventAndReportsAnInterruptedTimedCollection() {
        AsyncEventQueue queue = new AsyncEventQueue(16);
        RecordingBatchSink delegate = new RecordingBatchSink();
        AsyncBatchDelivery delivery = delivery(queue, delegate, 4, Long.MAX_VALUE);
        LogEvent first = event(1);
        queue.offerImmediately(first, () -> true);

        assertTrue(delivery.deliver(queue.claimNow(), ignored -> {
            throw new InterruptedException("injected");
        }));

        assertEquals(List.of(first), delegate.batch());
        assertEquals(0, queue.outstanding());
    }

    private static AsyncBatchDelivery delivery(
            AsyncEventQueue queue,
            RecordingBatchSink delegate,
            int maximumSize,
            long maximumDelayNanos) {
        AsyncSinkMetrics metrics = new AsyncSinkMetrics();
        AsyncDelegateDelivery delivery = new AsyncDelegateDelivery(delegate, metrics, new AsyncSinkDiagnostics("test"));
        return new AsyncBatchDelivery(queue, new AsyncBatchPolicy(delegate, maximumSize, maximumDelayNanos), delivery);
    }

    private static LogEvent event(int sequence) {
        return new LogEvent(
                1L,
                1_000_000L,
                Level.INFO,
                "test.Logger",
                null,
                "event",
                new Object[] {sequence},
                AttributeSet.EMPTY,
                null,
                1L,
                "test");
    }

    private static final class RecordingBatchSink implements BatchEventSink {
        private List<LogEvent> batch = List.of();

        @Override
        public int maximumBatchSize() {
            return 4;
        }

        @Override
        public Duration maximumBatchDelay() {
            return Duration.ofNanos(1L);
        }

        @Override
        public void acceptBatch(List<LogEvent> events) {
            batch = List.copyOf(events);
        }

        private List<LogEvent> batch() {
            return batch;
        }
    }
}
