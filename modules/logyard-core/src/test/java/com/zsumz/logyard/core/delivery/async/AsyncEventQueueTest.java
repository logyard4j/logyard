package com.zsumz.logyard.core.delivery.async;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncEventQueueTest {
    @Test
    void capacityRemainsFixedDuringConcurrentOffersAndClaims() throws Exception {
        AsyncEventQueue queue = new AsyncEventQueue(16);
        LogEvent event = event();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var producer = workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int index = 0; index < 200_000; index++) {
                    queue.offerImmediately(event, () -> true);
                }
                return null;
            });
            var consumer = workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int index = 0; index < 200_000; index++) {
                    if (queue.claimNow() != null) {
                        queue.completeClaims(1);
                    }
                }
                return null;
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (int index = 0; index < 200_000; index++) {
                assertEquals(16, queue.capacity());
            }
            producer.get(5, TimeUnit.SECONDS);
            consumer.get(5, TimeUnit.SECONDS);
        }
        while (queue.claimNow() != null) {
            queue.completeClaims(1);
        }
        assertEquals(16, queue.capacity());
        assertEquals(0, queue.queued());
        assertTrue(queue.awaitQuiescence(Duration.ZERO));
    }

    @Test
    void unclaimedAndOutstandingCountsIncludeOffersWaitingForQueueSpace() throws Exception {
        AsyncEventQueue queue = new AsyncEventQueue(1);
        LogEvent event = event();
        queue.offerImmediately(event, () -> true);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var pending = workers.submit(() -> queue.offerWithin(event, Duration.ofSeconds(5), () -> true));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (queue.queued() != 2 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, queue.capacity());
            assertEquals(2, queue.queued());
            assertEquals(2, queue.outstanding());
            assertSame(event, queue.claimNow());
            assertEquals(AsyncEventQueue.OfferResult.ENQUEUED, pending.get(5, TimeUnit.SECONDS));
            assertEquals(1, queue.queued());
            assertEquals(2, queue.outstanding());
            queue.completeClaims(1);
            assertSame(event, queue.claimNow());
            queue.completeClaims(1);
            assertTrue(queue.awaitQuiescence(Duration.ZERO));
        }
    }

    @Test
    void keepsClaimedEventsOutstandingUntilDeliveryCompletes() {
        AsyncEventQueue queue = new AsyncEventQueue(16);
        LogEvent event = event();

        assertEquals(AsyncEventQueue.OfferResult.ENQUEUED, queue.offerImmediately(event, () -> true));
        assertEquals(1, queue.queued());
        assertEquals(1, queue.outstanding());
        assertSame(event, queue.claimNow());
        assertEquals(0, queue.queued());
        assertEquals(1, queue.outstanding());
        assertFalse(queue.awaitQuiescence(Duration.ZERO));

        queue.completeClaims(1);

        assertTrue(queue.awaitQuiescence(Duration.ZERO));
    }

    @Test
    void retractsAnOfferWhenShutdownWinsTheAcceptanceRace() {
        AsyncEventQueue queue = new AsyncEventQueue(16);

        assertEquals(AsyncEventQueue.OfferResult.CLOSED, queue.offerImmediately(event(), () -> false));
        assertEquals(0, queue.queued());
        assertEquals(0, queue.outstanding());
        assertTrue(queue.awaitQuiescence(Duration.ZERO));
    }

    private static LogEvent event() {
        return new LogEvent(
                1L,
                1_000_000L,
                Level.INFO,
                "test.Logger",
                null,
                "hello",
                new Object[0],
                AttributeSet.EMPTY,
                null,
                1L,
                "test");
    }
}
