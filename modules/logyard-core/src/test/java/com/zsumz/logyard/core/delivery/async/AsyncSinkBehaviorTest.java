package com.zsumz.logyard.core.delivery.async;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.delivery.OverflowAction;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.BatchEventSink;
import com.zsumz.logyard.api.spi.output.EventSink;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncSinkBehaviorTest {
    /**
     * Bound for every handshake with the worker. Each handshake waits for a signal the delivery
     * machinery always produces, so this budget is only ever reached by a genuine hang and never
     * by machine load.
     */
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);

    /** Shutdown and flush deadline for the outputs whose worker is never parked by the test. */
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Shutdown and flush deadline for the flush barrier. The barrier deliberately parks the worker
     * inside the delegate, so this deadline stays far above {@link #BARRIER_OBSERVATION}: a blocked
     * flush must never be able to turn into an expired one just because the machine is busy.
     */
    private static final Duration BARRIER_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    /**
     * How long a blocked flush is observed. The barrier makes progress impossible until the test
     * releases the delegate, so a slow machine can only lengthen the observation, never break it.
     */
    private static final Duration BARRIER_OBSERVATION = Duration.ofMillis(100);

    @Test
    void rejectsCapacityOutsideTheBoundedContract() {
        assertThrows(IllegalArgumentException.class, () -> new AsyncSink(
                "too-large",
                ignored -> { },
                AsyncSink.MAX_CAPACITY + 1,
                new OverflowPolicy(Map.of()),
                Duration.ZERO));
    }

    @Test
    void boundsDeliveryAndAccountsForDrops() throws Exception {
        BlockingSink delegate = new BlockingSink();
        AsyncSink sink = sink("test", delegate, OverflowAction.DROP, true);
        try {
            sink.accept(event(AttributeSet.EMPTY));
            assertTrue(
                    delegate.entered.await(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "the worker never entered the delegate");

            // The worker is parked inside the delegate until this test releases it, so nothing can
            // leave the queue and the counts below are exact rather than merely likely.
            for (int index = 0; index < 16; index++) {
                sink.accept(event(AttributeSet.EMPTY));
            }
            sink.accept(event(AttributeSet.EMPTY));
            assertEquals(16, sink.queued());
            assertEquals(17L, sink.queuedEvents());
            assertEquals(1L, sink.dropped(Level.INFO));

            // Wait for the backlog on the counter the worker itself advances, so the delivery
            // assertions never depend on the shutdown deadline winning a race with the scheduler.
            delegate.release.countDown();
            awaitCondition(() -> sink.deliveredEvents() == 17L, HANDSHAKE_TIMEOUT);
            assertEquals(17, delegate.applicationEvents.get());
        } finally {
            delegate.release.countDown();
            sink.close();
        }
        assertEquals(17L, sink.deliveredEvents());
        assertEquals(17, delegate.applicationEvents.get());
    }

    @Test
    void batchesOnTheOwnedWorkerWithoutCallerThreadDelivery() {
        RecordingBatchSink delegate = new RecordingBatchSink();
        AsyncSink sink = sink("batch-test", delegate, OverflowAction.SYNC, false);
        try {
            for (int index = 0; index < 7; index++) {
                sink.accept(event(AttributeSet.of("sequence", index)));
            }
            sink.flush();
        } finally {
            sink.close();
        }
        assertEquals(7, delegate.events.get());
        assertTrue(delegate.batchSizes.stream().allMatch(size -> size >= 1 && size <= 4));
        assertTrue(delegate.threads.stream().allMatch(name -> name.startsWith("logyard-output-")));
        assertEquals(0L, sink.synchronousFallbacks());
    }

    @Test
    void flushWaitsForAClaimedBatchThatHasLeftTheQueue() throws Exception {
        HeldBatchSink delegate = new HeldBatchSink();
        AsyncSink sink = sink("flush-barrier", delegate, OverflowAction.DROP, false, BARRIER_SHUTDOWN_TIMEOUT);
        CountDownLatch flushReturned = new CountDownLatch(1);
        Thread flusher = new Thread(
                () -> {
                    sink.flush();
                    flushReturned.countDown();
                },
                "flush-barrier-test");
        try {
            sink.accept(event(AttributeSet.EMPTY));
            assertTrue(
                    delegate.entered.await(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "the worker never handed the claimed batch to the delegate");

            // The worker is parked inside the delegate, so the batch has left the queue while its
            // claim is still outstanding. That state is held by a latch, not by a batching window.
            Map<String, Long> barrier = sink.health("flush-barrier").metrics();
            assertEquals(0, sink.queued());
            assertEquals(1L, barrier.get("active_deliveries"));
            assertEquals(1L, barrier.get("outstanding_queued_events"));

            flusher.start();
            assertFalse(
                    flushReturned.await(BARRIER_OBSERVATION.toMillis(), TimeUnit.MILLISECONDS),
                    "flush returned while a claimed batch was still outstanding");
            assertTrue(flusher.isAlive());
            assertEquals(0, delegate.events.get());

            delegate.release.countDown();
            assertTrue(
                    flushReturned.await(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "flush did not return once the claim completed");
            flusher.join(HANDSHAKE_TIMEOUT.toMillis());
            assertFalse(flusher.isAlive());
            assertEquals(1, delegate.events.get());
        } finally {
            delegate.release.countDown();
            sink.close();
        }
    }

    @Test
    void keepsTheWorkerAliveAfterARecoverableDelegateError() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        EventSink delegate = event -> {
            if (attempts.getAndIncrement() == 0) {
                throw new AssertionError("hostile sink");
            }
            delivered.incrementAndGet();
        };
        AsyncSink sink = sink("hostile", delegate, OverflowAction.DROP, false);
        try {
            sink.accept(event(AttributeSet.of("sequence", 1)));
            sink.accept(event(AttributeSet.of("sequence", 2)));
            sink.flush();

            assertEquals(2, attempts.get());
            assertEquals(1, delivered.get());
            assertEquals(1L, sink.emergencyFallbacks());
            assertEquals("true", sink.health("hostile").details().get("worker_alive"));
        } finally {
            sink.close();
        }
    }

    private static AsyncSink sink(String name, EventSink delegate, OverflowAction action, boolean callerThreadDeliveryAllowed) {
        return sink(name, delegate, action, callerThreadDeliveryAllowed, SHUTDOWN_TIMEOUT);
    }

    private static AsyncSink sink(
            String name,
            EventSink delegate,
            OverflowAction action,
            boolean callerThreadDeliveryAllowed,
            Duration shutdownTimeout) {
        return new AsyncSink(
                name,
                delegate,
                16,
                new OverflowPolicy(Map.of(Level.INFO, new OverflowPolicy.Rule(action, Duration.ZERO))),
                shutdownTimeout,
                callerThreadDeliveryAllowed);
    }

    private static LogEvent event(AttributeSet attributes) {
        return new LogEvent(1, 2, Level.INFO, "test", null, "message", null, attributes, null, 1, "main");
    }

    private static void awaitCondition(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition did not become true within " + timeout);
            }
            Thread.sleep(1);
        }
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("test delegate was not released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class BlockingSink implements EventSink {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final AtomicInteger applicationEvents = new AtomicInteger();

        @Override
        public void accept(LogEvent event) {
            if ("logyard.internal.async".equals(event.loggerName())) {
                return;
            }
            applicationEvents.incrementAndGet();
            if (first.compareAndSet(true, false)) {
                entered.countDown();
                awaitRelease(release);
            }
        }
    }

    private static final class RecordingBatchSink implements BatchEventSink {
        private final AtomicInteger events = new AtomicInteger();
        private final List<Integer> batchSizes = new CopyOnWriteArrayList<>();
        private final List<String> threads = new CopyOnWriteArrayList<>();

        @Override
        public int maximumBatchSize() {
            return 4;
        }

        @Override
        public Duration maximumBatchDelay() {
            return Duration.ofMillis(10);
        }

        @Override
        public void acceptBatch(List<LogEvent> batch) {
            batchSizes.add(batch.size());
            threads.add(Thread.currentThread().getName());
            events.addAndGet(batch.size());
        }
    }

    /** Holds the worker inside one claimed batch until the test releases it. */
    private static final class HeldBatchSink implements BatchEventSink {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger events = new AtomicInteger();

        @Override
        public int maximumBatchSize() {
            return 4;
        }

        @Override
        public Duration maximumBatchDelay() {
            return Duration.ofMillis(250);
        }

        @Override
        public void acceptBatch(List<LogEvent> batch) {
            entered.countDown();
            awaitRelease(release);
            events.addAndGet(batch.size());
        }
    }
}
