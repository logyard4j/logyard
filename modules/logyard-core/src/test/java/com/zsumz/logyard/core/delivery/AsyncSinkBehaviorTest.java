package com.zsumz.logyard.core.delivery;

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
            assertTrue(delegate.entered.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 16; index++) {
                sink.accept(event(AttributeSet.EMPTY));
            }
            sink.accept(event(AttributeSet.EMPTY));
            assertEquals(16, sink.queued());
            assertEquals(17L, sink.queuedEvents());
            assertEquals(1L, sink.dropped(Level.INFO));
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
        DelayedBatchSink delegate = new DelayedBatchSink();
        AsyncSink sink = sink("flush-barrier", delegate, OverflowAction.DROP, false);
        try {
            sink.accept(event(AttributeSet.EMPTY));
            awaitCondition(
                    () -> sink.queued() == 0 && sink.health("flush-barrier").metrics().get("active_deliveries") == 1L,
                    Duration.ofSeconds(1));

            Thread flusher = new Thread(sink::flush, "flush-barrier-test");
            flusher.start();
            Thread.sleep(25);
            assertTrue(flusher.isAlive());
            flusher.join(2_000);
            assertFalse(flusher.isAlive());
            assertEquals(1, delegate.events.get());
        } finally {
            sink.close();
        }
    }

    private static AsyncSink sink(String name, EventSink delegate, OverflowAction action, boolean callerThreadDeliveryAllowed) {
        return new AsyncSink(
                name,
                delegate,
                16,
                new OverflowPolicy(Map.of(Level.INFO, new OverflowPolicy.Rule(action, Duration.ZERO))),
                Duration.ofSeconds(2),
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
                awaitRelease();
            }
        }

        private void awaitRelease() {
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("test delegate was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
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

    private static final class DelayedBatchSink implements BatchEventSink {
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
            events.addAndGet(batch.size());
        }
    }
}
