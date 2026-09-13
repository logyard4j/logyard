package com.logyard4j.core.runtime;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.routing.RouteDefinition;
import com.logyard4j.core.runtime.retirement.RuntimeRetirements;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeRetirementCompletionTest {
    @Test
    void closeWaitsForLifecycleCompletionAfterOutputBookkeepingFinishes() throws Exception {
        exerciseCompletionBoundary(Duration.ofSeconds(5), false);
    }

    @Test
    void delayedLifecycleCompletionStillRespectsTheShutdownDeadline() throws Exception {
        exerciseCompletionBoundary(Duration.ofMillis(250), true);
    }

    private static void exerciseCompletionBoundary(Duration timeout, boolean deadline) throws Exception {
        GateSink sink = new GateSink();
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("sink"), List.of()),
                Map.of(), Map.of("sink", sink), Map.of(), timeout));
        CompletionQueue pending = installCompletionQueue(runtime);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var closing = executor.submit(runtime::close);
            assertTrue(sink.entered.await(2, TimeUnit.SECONDS));
            awaitLifecycleObserver(pending);
            sink.release.countDown();
            assertTrue(pending.removed.await(2, TimeUnit.SECONDS));
            assertFalse(runtime.retirementCompletion().toCompletableFuture().isDone());
            if (deadline) {
                closing.get(2, TimeUnit.SECONDS);
                assertFalse(runtime.retirementCompletion().toCompletableFuture().isDone());
            } else {
                assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
            }

            pending.release.countDown();
            closing.get(2, TimeUnit.SECONDS);
            runtime.retirementCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            runtime.close();
            assertEquals(1, sink.closes.get());
        } finally {
            sink.release.countDown();
            pending.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            runtime.close();
        }
    }

    private static CompletionQueue installCompletionQueue(DefaultLogyardRuntime runtime) throws Exception {
        Field retirementsField = DefaultLogyardRuntime.class.getDeclaredField("retirements");
        retirementsField.setAccessible(true);
        Field pendingField = RuntimeRetirements.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        CompletionQueue pending = new CompletionQueue();
        pendingField.set(retirementsField.get(runtime), pending);
        return pending;
    }

    private static void awaitLifecycleObserver(CompletionQueue pending) throws InterruptedException {
        long started = System.nanoTime();
        while (true) {
            CompletableFuture<?> observation = (CompletableFuture<?>) pending.peek();
            if (observation != null && observation.getNumberOfDependents() > 0) {
                return;
            }
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2),
                    "lifecycle completion observer was not installed");
            Thread.sleep(1);
        }
    }

    /** Scheduling-only hook: preserve queue operations while pausing removal before completion publication. */
    private static final class CompletionQueue extends ConcurrentLinkedQueue<CompletableFuture<Void>> {
        private final CountDownLatch removed = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean remove(Object value) {
            boolean result = super.remove(value);
            if (result) {
                removed.countDown();
                await(release);
            }
            return result;
        }

        @Override
        public Iterator<CompletableFuture<Void>> iterator() {
            await(removed);
            return super.iterator();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interruption);
        }
    }

    private static final class GateSink implements EventSink {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public void accept(LogEvent event) {
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            entered.countDown();
            await(release);
        }
    }
}
