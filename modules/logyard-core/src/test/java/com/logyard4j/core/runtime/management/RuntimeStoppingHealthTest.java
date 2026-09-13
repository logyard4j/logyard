package com.logyard4j.core.runtime.management;

import com.logyard4j.api.Level;
import com.logyard4j.api.diagnostics.HealthStatus;
import com.logyard4j.api.diagnostics.RuntimeHealth;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.routing.RouteDefinition;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.core.runtime.RuntimePlan;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeStoppingHealthTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unfinishedRetirementRemainsStoppingThroughStalledPublicationOrClose(boolean stalledPublication) throws Exception {
        GateSink sink = new GateSink(stalledPublication);
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("sink"), List.of()),
                Map.of(), Map.of("sink", sink), Map.of(), Duration.ofMillis(20)));
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> publication = stalledPublication
                    ? executor.submit(() -> runtime.logger("test").info("held publisher")) : null;
            if (stalledPublication) assertTrue(sink.entered.await(2, TimeUnit.SECONDS));
            runtime.close();
            assertTrue(sink.entered.await(2, TimeUnit.SECONDS));
            assertFalse(runtime.retirementCompletion().toCompletableFuture().isDone());

            RuntimeHealth health = executor.submit(runtime::health).get(500, TimeUnit.MILLISECONDS);

            assertEquals(HealthStatus.STOPPING, health.status());
            assertFalse(health.ready());
            assertEquals(HealthStatus.STOPPING, health.components().getFirst().status());
            assertEquals("true", health.components().getFirst().details().get("closed"));
            assertEquals(1L, health.components().getFirst().metrics().get("pending_retirements"));
            sink.release.countDown();
            if (publication != null) publication.get(2, TimeUnit.SECONDS);
            runtime.retirementCompletion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(HealthStatus.STOPPED, runtime.health().status());
            assertFalse(runtime.health().ready());
            runtime.close();
            assertEquals(1, sink.closes.get());
        } finally {
            sink.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            runtime.close();
            runtime.retirementCompletion().toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
    }

    private static final class GateSink implements EventSink {
        private final boolean stalledPublication;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger closes = new AtomicInteger();

        private GateSink(boolean stalledPublication) {
            this.stalledPublication = stalledPublication;
        }

        @Override
        public void accept(LogEvent event) {
            if (stalledPublication) pause();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            if (!stalledPublication) pause();
        }

        private void pause() {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interruption);
            }
        }
    }
}
