package com.logyard4j.logyard.core.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.routing.RouteDefinition;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class DefaultLogyardRuntimeLifecycleTest {
    @Test
    void sinkCloseCanRequestALoggerFromAHelperWithoutWaitingForTheRuntimeMonitor() throws Exception {
        ExecutorService helper = Executors.newSingleThreadExecutor();
        DefaultLogyardRuntime[] runtime = new DefaultLogyardRuntime[1];
        EventSink sink = new EventSink() {
            @Override
            public void accept(LogEvent event) {
            }

            @Override
            public void close() {
                try {
                    helper.submit(() -> runtime[0].logger("close.callback")).get();
                } catch (ExecutionException failure) {
                    throw new AssertionError("logger lookup failed during sink close", failure.getCause());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                } finally {
                    helper.shutdownNow();
                }
            }
        };
        runtime[0] = new DefaultLogyardRuntime(plan(sink, Duration.ofSeconds(1)));

        runtime[0].close();

        assertTrue(helper.awaitTermination(1, TimeUnit.SECONDS));
    }

    @Test
    void closeDeadlineDoesNotPretendThatOutputRetirementHasFinished() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EventSink sink = new EventSink() {
            @Override
            public void accept(LogEvent event) {
            }

            @Override
            public void close() {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(plan(sink, Duration.ofMillis(20)));

        runtime.close();

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertFalse(runtime.retirementCompletion().toCompletableFuture().isDone());
        release.countDown();
        runtime.retirementCompletion().toCompletableFuture().orTimeout(1, TimeUnit.SECONDS).join();
    }

    private static RuntimePlan plan(EventSink sink, Duration timeout) {
        return new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("sink"), List.of()),
                Map.of(),
                Map.of("sink", sink),
                Map.of(),
                timeout);
    }
}
