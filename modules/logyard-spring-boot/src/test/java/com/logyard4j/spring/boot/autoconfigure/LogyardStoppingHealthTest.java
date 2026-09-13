package com.logyard4j.spring.boot.autoconfigure;

import com.logyard4j.api.Level;
import com.logyard4j.api.Logyard;
import com.logyard4j.api.diagnostics.HealthStatus;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.delivery.async.AsyncSink;
import com.logyard4j.core.delivery.async.OverflowPolicy;
import com.logyard4j.core.routing.RouteDefinition;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.core.runtime.RuntimePlan;
import com.logyard4j.output.json.stream.JsonLinesSink;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.Writer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LogyardStoppingHealthTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void frameworkViewsReportPendingRuntimeRetirementWhileJsonCloseIsBlocked(boolean async) throws Exception {
        GateWriter writer = new GateWriter();
        JsonLinesSink sink = new JsonLinesSink(writer, event -> "{}", Duration.ofSeconds(1), true);
        EventSink output = async
                ? new AsyncSink("json", sink, 16, new OverflowPolicy(null), Duration.ofSeconds(5)) : sink;
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("json"), List.of()),
                Map.of(), Map.of("json", output), Map.of(), Duration.ofMillis(20)));
        var executor = Executors.newSingleThreadExecutor();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            Logyard.shutdown();
            Logyard.initialize(runtime);
            context.register(LogyardActuatorAutoConfiguration.class);
            context.refresh();
            HealthIndicator indicator = context.getBean("logyard", HealthIndicator.class);
            LogyardRuntimeHealth view = new LogyardRuntimeHealth(runtime);

            runtime.close();
            assertTrue(writer.entered.await(2, TimeUnit.SECONDS));
            assertFalse(runtime.retirementCompletion().toCompletableFuture().isDone());

            var health = executor.submit(indicator::health).get(500, TimeUnit.MILLISECONDS);
            assertEquals(Status.DOWN, health.getStatus());
            assertEquals("STOPPING", health.getDetails().get("status"));
            assertEquals(false, health.getDetails().get("ready"));
            assertEquals(HealthStatus.STOPPING, executor.submit(view::snapshot)
                    .get(500, TimeUnit.MILLISECONDS).status());
            assertEquals(1L, writer.release.getCount());

            writer.release.countDown();
            runtime.retirementCompletion().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals("STOPPED", indicator.health().getDetails().get("status"));
            assertEquals(HealthStatus.STOPPED, view.snapshot().status());
        } finally {
            writer.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            Logyard.shutdownIfCurrent(runtime);
            runtime.close();
            runtime.retirementCompletion().toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
    }

    private static final class GateWriter extends Writer {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(char[] value, int offset, int length) {
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
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
