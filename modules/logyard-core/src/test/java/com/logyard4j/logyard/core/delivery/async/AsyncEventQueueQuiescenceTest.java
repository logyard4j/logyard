package com.logyard4j.logyard.core.delivery.async;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncEventQueueQuiescenceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void zeroTimeoutPreservesInterruptionAndOutstandingClaims(boolean pending) {
        AsyncEventQueue queue = pending ? queueWithClaim() : new AsyncEventQueue(1);
        Thread.currentThread().interrupt();
        try {
            assertEquals(!pending, queue.awaitQuiescence(Duration.ZERO));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, queue.queued());
            assertEquals(pending ? 1 : 0, queue.outstanding());
        } finally {
            Thread.interrupted();
            if (pending) {
                queue.completeClaims(1);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true", "false,true", "true,false", "false,false"})
    void interruptedWaitParksUntilQuiescenceOrDeadline(boolean initiallyInterrupted, boolean complete)
            throws Exception {
        AsyncEventQueue queue = queueWithClaim();
        Duration timeout = Duration.ofSeconds(2);
        FutureTask<WaitResult> result = new FutureTask<>(() -> {
            if (initiallyInterrupted) {
                Thread.currentThread().interrupt();
            }
            long started = System.nanoTime();
            boolean quiescent = queue.awaitQuiescence(timeout);
            return new WaitResult(quiescent, Thread.currentThread().isInterrupted(), System.nanoTime() - started);
        });
        Thread waiter = Thread.ofPlatform().daemon().unstarted(result);
        waiter.start();
        try {
            if (!initiallyInterrupted) {
                awaitParked(waiter);
                waiter.interrupt();
            }
            // A spinning interrupted park stays RUNNABLE with its interrupt set. Require a real wait.
            awaitParked(waiter);
            assertFalse(result.isDone());
            assertEquals(0, queue.queued());
            assertEquals(1, queue.outstanding());
            if (complete) {
                queue.completeClaims(1);
            }
            WaitResult observed = result.get(5, TimeUnit.SECONDS);
            assertEquals(complete, observed.quiescent());
            assertTrue(observed.interrupted());
            assertEquals(complete ? 0 : 1, queue.outstanding());
            if (!complete) {
                assertTrue(observed.elapsedNanos() >= timeout.toNanos(), "interruption shortened the deadline");
            }
        } finally {
            if (queue.outstanding() != 0) {
                queue.completeClaims(1);
            }
            waiter.join(5_000);
            assertFalse(waiter.isAlive());
        }
    }

    private static void awaitParked(Thread waiter) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (waiter.getState() == Thread.State.TIMED_WAITING && !waiter.isInterrupted()) {
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("quiescence waiter did not park with interruption temporarily cleared");
    }

    private static AsyncEventQueue queueWithClaim() {
        AsyncEventQueue queue = new AsyncEventQueue(1);
        LogEvent event = new LogEvent(1L, 1_000_000L, Level.INFO, "test.Logger", null, "hello",
                new Object[0], AttributeSet.EMPTY, null, 1L, "test");
        assertEquals(AsyncEventQueue.OfferResult.ENQUEUED, queue.offerImmediately(event, () -> true));
        assertSame(event, queue.claimNow());
        return queue;
    }

    private record WaitResult(boolean quiescent, boolean interrupted, long elapsedNanos) {
    }
}
