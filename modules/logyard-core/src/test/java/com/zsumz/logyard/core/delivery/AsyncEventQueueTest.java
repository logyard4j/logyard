package com.zsumz.logyard.core.delivery;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncEventQueueTest {
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
