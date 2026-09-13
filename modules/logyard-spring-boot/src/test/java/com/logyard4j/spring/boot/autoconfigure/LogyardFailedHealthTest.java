package com.logyard4j.spring.boot.autoconfigure;

import com.logyard4j.api.Level;
import com.logyard4j.api.Logyard;
import com.logyard4j.api.diagnostics.ComponentHealth;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.diagnostics.HealthContributor;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.delivery.async.AsyncSink;
import com.logyard4j.core.delivery.async.OverflowPolicy;
import com.logyard4j.core.routing.RouteDefinition;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.core.runtime.RuntimePlan;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LogyardFailedHealthTest {
    @Test
    void actuatorReportsFailureWithoutReadingTheExceptionsMessage() throws Exception {
        FailingHealthSink delegate = new FailingHealthSink();
        var output = new AsyncSink("probe", delegate, 16, new OverflowPolicy(null), Duration.ofSeconds(2));
        var runtime = new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("probe"), List.of()),
                Map.of(), Map.of("probe", output), Map.of()));
        var executor = Executors.newSingleThreadExecutor();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            Logyard.shutdown();
            Logyard.initialize(runtime);
            context.register(LogyardActuatorAutoConfiguration.class);
            context.refresh();
            HealthIndicator indicator = context.getBean("logyard", HealthIndicator.class);

            var pending = executor.submit(indicator::health);
            assertTrue(delegate.healthCalled.await(1, TimeUnit.SECONDS));
            var health = pending.get(1, TimeUnit.SECONDS);
            assertEquals(Status.DOWN, health.getStatus());
            assertEquals(0, delegate.failure.messageCalls.get());
            assertEquals(1L, delegate.failure.release.getCount());
        } finally {
            delegate.failure.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            Logyard.shutdownIfCurrent(runtime);
            runtime.close();
        }
    }

    private static final class FailingHealthSink implements EventSink, HealthContributor {
        private final BlockingMessageFailure failure = new BlockingMessageFailure();
        private final CountDownLatch healthCalled = new CountDownLatch(1);

        @Override
        public void accept(LogEvent event) {
        }

        @Override
        public ComponentHealth health(String componentName) {
            healthCalled.countDown();
            throw failure;
        }
    }

    private static final class BlockingMessageFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger messageCalls = new AtomicInteger();

        @Override
        public String getMessage() {
            messageCalls.incrementAndGet();
            try {
                release.await();
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
            return "health contributor failed";
        }
    }
}
