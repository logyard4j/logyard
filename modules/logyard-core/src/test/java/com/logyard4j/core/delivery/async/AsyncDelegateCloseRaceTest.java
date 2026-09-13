package com.logyard4j.core.delivery.async;

import com.logyard4j.api.Level;
import com.logyard4j.api.delivery.OverflowAction;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.output.BatchEventSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncDelegateCloseRaceTest {
    @ParameterizedTest
    @EnumSource(OverflowAction.class)
    void callerDeliveryPausedBeforeTheLockCannotAcceptAfterClose(OverflowAction action) throws Exception {
        GateLock lock = new GateLock();
        RecordingDelegate delegate = new RecordingDelegate();
        AsyncSinkMetrics metrics = new AsyncSinkMetrics();
        AsyncSinkDiagnostics diagnostics = new AsyncSinkDiagnostics("close-race");
        OverflowPolicy policy = new OverflowPolicy(Map.of(Level.INFO, new OverflowPolicy.Rule(action, Duration.ZERO)));
        AsyncDelegateDelivery delivery = new AsyncDelegateDelivery(delegate, metrics, diagnostics, policy, lock);
        AsyncDeliveryLoop loop = new AsyncDeliveryLoop("close-race", new AsyncEventQueue(16),
                metrics, diagnostics, delivery, new AsyncWorkerLifecycle());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var fallback = executor.submit(() -> {
                lock.pauseCurrentThread();
                loop.deliverOnCallerThread(event());
            });
            assertTrue(lock.entered.await(2, TimeUnit.SECONDS));
            assertEquals(1, loop.activeDeliveries());
            loop.closeDelegate("close-race");
            assertEquals(1, delegate.closes.get());
            lock.release.countDown();
            fallback.get(2, TimeUnit.SECONDS);
            loop.closeDelegate("close-race");

            assertEquals(0, delegate.accepts.get());
            assertEquals(0L, metrics.delivered());
            assertEquals(policy.dropsUndelivered(Level.INFO) ? 1L : 0L, metrics.dropped(Level.INFO));
            assertEquals(policy.dropsUndelivered(Level.INFO) ? 0L : 1L, metrics.emergencyFallbacks());
            assertEquals(0, loop.activeDeliveries());
            assertEquals(1, delegate.closes.get());
        } finally {
            lock.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void closedDelegateRejectsBatchesInternalEventsAndFlushes() {
        RecordingDelegate delegate = new RecordingDelegate();
        AsyncSinkMetrics metrics = new AsyncSinkMetrics();
        AsyncDelegateDelivery delivery = new AsyncDelegateDelivery(delegate, metrics, new AsyncSinkDiagnostics("closed"));
        delivery.close("closed");
        delivery.deliverBatch(List.of(event(), event()));
        delivery.deliverInternalEvent(event());
        delivery.flush();
        delivery.close("closed");

        assertEquals(0, delegate.accepts.get());
        assertEquals(0L, metrics.delivered());
        assertEquals(2L, metrics.dropped(Level.INFO));
        assertEquals(1, delegate.flushes.get());
        assertEquals(1, delegate.closes.get());
    }

    private static LogEvent event() {
        return new LogEvent(0L, 0L, Level.INFO, "test", "test", "record", null, AttributeSet.EMPTY, null, 1L, "test");
    }

    private static final class GateLock extends ReentrantLock {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private Thread paused;

        private void pauseCurrentThread() {
            paused = Thread.currentThread();
        }

        @Override
        public void lock() {
            if (Thread.currentThread() == paused) {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interruption);
                }
            }
            super.lock();
        }
    }

    private static final class RecordingDelegate implements BatchEventSink {
        private final AtomicInteger accepts = new AtomicInteger();
        private final AtomicInteger flushes = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public int maximumBatchSize() {
            return 16;
        }

        @Override
        public Duration maximumBatchDelay() {
            return Duration.ZERO;
        }

        @Override
        public void accept(LogEvent event) {
            accepts.incrementAndGet();
        }

        @Override
        public void acceptBatch(List<LogEvent> events) {
            accepts.addAndGet(events.size());
        }

        @Override
        public void flush() {
            flushes.incrementAndGet();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
