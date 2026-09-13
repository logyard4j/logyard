package com.logyard4j.logyard.core.runtime;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.delivery.OverflowAction;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.delivery.async.AsyncSink;
import com.logyard4j.logyard.core.delivery.async.OverflowPolicy;
import com.logyard4j.logyard.core.routing.RouteDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeFallbackRetirementTest {
    @Test
    void epochLeaseProtectsSynchronousFallbackAfterTheShutdownDeadline() throws Exception {
        GateSink delegate = new GateSink();
        AsyncSink sink = new AsyncSink("test", delegate, 16,
                new OverflowPolicy(Map.of(Level.INFO, new OverflowPolicy.Rule(OverflowAction.SYNC, Duration.ZERO))),
                Duration.ofSeconds(2));
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("test"), List.of()),
                Map.of(), Map.of("test", sink), Map.of(), Duration.ofMillis(20)));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var logger = runtime.logger("test");
            logger.info("worker");
            assertTrue(delegate.entered.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 16; index++) {
                logger.info("queued");
            }
            var fallback = executor.submit(() -> logger.info("fallback"));
            long started = System.nanoTime();
            while (sink.health("test").metrics().get("active_deliveries") != 2L) {
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "fallback did not enter delivery");
                LockSupport.parkNanos(100_000L);
            }
            assertEquals(1L, sink.synchronousFallbacks());
            runtime.close();
            assertFalse(runtime.retirementCompletion().toCompletableFuture().isDone());
            assertEquals(0, delegate.closes.get());
            assertFalse(fallback.isDone());

            delegate.release.countDown();
            fallback.get(2, TimeUnit.SECONDS);
            runtime.retirementCompletion().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(18, delegate.accepts.get());
            assertEquals(0, delegate.afterClose.get());
            assertEquals(1, delegate.closes.get());
            assertEquals(18L, sink.health("test").metrics().get("delivered_total"));
            assertEquals(0L, sink.dropped(Level.INFO));
        } finally {
            delegate.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            runtime.close();
        }
    }

    private static final class GateSink implements EventSink {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger accepts = new AtomicInteger();
        private final AtomicInteger afterClose = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public void accept(LogEvent event) {
            if (closes.get() != 0) {
                afterClose.incrementAndGet();
            }
            if (accepts.getAndIncrement() == 0) {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interruption);
                }
            }
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
