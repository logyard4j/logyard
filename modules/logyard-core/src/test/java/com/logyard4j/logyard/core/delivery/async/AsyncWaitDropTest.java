package com.logyard4j.logyard.core.delivery.async;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.delivery.OverflowAction;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.output.EventSink;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncWaitDropTest {
    @Test
    void dropsOnZeroOrExpiredWaitWithoutCallerOutput() throws Exception {
        for (Duration timeout : new Duration[] {Duration.ZERO, Duration.ofMillis(20)}) {
            try (Saturated fixture = new Saturated(timeout, Duration.ofSeconds(2))) {
                long started = System.nanoTime();
                fixture.sink.accept(event());
                long elapsed = System.nanoTime() - started;
                assertTrue(elapsed >= timeout.toNanos());
                assertEquals(1L, fixture.sink.dropped(Level.ERROR));
                assertEquals(17L, fixture.sink.queuedEvents());
                assertEquals(1, fixture.writes.get());
                fixture.requireNoCallerFallback();
            }
        }
    }

    @Test
    void acceptsWhenCapacityReturnsWithinTheWait() throws Exception {
        try (Saturated fixture = new Saturated(Duration.ofSeconds(2), Duration.ofSeconds(2))) {
            Thread caller = new Thread(() -> fixture.sink.accept(event()), "wait-drop-admission");
            caller.start();
            try {
                fixture.awaitWaitingOffer();
                fixture.release.countDown();
                caller.join(2_000);
                assertFalse(caller.isAlive());
                fixture.sink.flush();
                assertEquals(18L, fixture.sink.queuedEvents());
                assertEquals(18, fixture.writes.get());
                assertEquals(0L, fixture.sink.dropped(Level.ERROR));
                fixture.requireNoCallerFallback();
            } finally {
                fixture.release.countDown();
                caller.interrupt();
                caller.join(2_000);
            }
        }
    }

    @Test
    void preservesInterruptionAndCountsOneDrop() throws Exception {
        try (Saturated fixture = new Saturated(Duration.ofSeconds(10), Duration.ofSeconds(2))) {
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread caller = new Thread(() -> {
                fixture.sink.accept(event());
                interrupted.set(Thread.currentThread().isInterrupted());
            }, "wait-drop-interruption");
            caller.start();
            try {
                fixture.awaitWaitingOffer();
                caller.interrupt();
                caller.join(2_000);
                assertFalse(caller.isAlive());
                assertTrue(interrupted.get());
                assertEquals(1L, fixture.sink.dropped(Level.ERROR));
                assertEquals(16, fixture.sink.queued());
                fixture.requireNoCallerFallback();
            } finally {
                caller.interrupt();
                caller.join(2_000);
            }
        }
    }

    @Test
    void dropsUndeliveredAndLateEventsWhenShutdownExpires() throws Exception {
        try (Saturated fixture = new Saturated(Duration.ofMillis(20), Duration.ZERO)) {
            fixture.sink.close();
            assertEquals(16L, fixture.sink.dropped(Level.ERROR));
            fixture.sink.accept(event());
            assertEquals(17L, fixture.sink.dropped(Level.ERROR));
            fixture.requireNoCallerFallback();
        }
    }

    private static LogEvent event() {
        return new LogEvent(1, 2, Level.ERROR, "example", null, "record", null,
                AttributeSet.EMPTY, null, 1, "caller");
    }

    private static final class Saturated implements AutoCloseable {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger writes = new AtomicInteger();
        private final AsyncSink sink;

        private Saturated(Duration wait, Duration shutdown) throws Exception {
            EventSink delegate = new EventSink() {
                @Override
                public void accept(LogEvent event) {
                    if (event.loggerName().equals("logyard.internal.async")) return;
                    writes.incrementAndGet();
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("delegate not released");
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interruption);
                    }
                }

                @Override
                public void close() {
                    closed.countDown();
                }
            };
            sink = new AsyncSink("wait-drop", delegate, 16, new OverflowPolicy(Map.of(
                    Level.ERROR, new OverflowPolicy.Rule(OverflowAction.WAIT_DROP, wait))), shutdown);
            sink.accept(event());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 16; index++) sink.accept(event());
        }

        private void awaitWaitingOffer() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (sink.queued() != 17 && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(17, sink.queued());
        }

        private void requireNoCallerFallback() {
            assertEquals(0L, sink.emergencyFallbacks());
            assertEquals(0L, sink.synchronousFallbacks());
        }

        @Override
        public void close() {
            release.countDown();
            sink.close();
            try {
                assertTrue(closed.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interruption);
            }
        }
    }
}
