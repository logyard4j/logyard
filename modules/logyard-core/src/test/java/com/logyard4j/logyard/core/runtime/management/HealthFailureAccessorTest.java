package com.logyard4j.logyard.core.runtime.management;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.diagnostics.ComponentHealth;
import com.logyard4j.logyard.api.diagnostics.HealthStatus;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.diagnostics.HealthContributor;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.delivery.async.AsyncSink;
import com.logyard4j.logyard.core.delivery.async.OverflowPolicy;
import com.logyard4j.logyard.core.routing.RouteDefinition;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.logyard.core.runtime.RuntimePlan;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HealthFailureAccessorTest {
    enum SnapshotPath { DIRECT_RUNTIME, ASYNC_RUNTIME, ASYNC_OUTPUT }

    @ParameterizedTest
    @EnumSource(SnapshotPath.class)
    void reportsFailureAndRecoveryWithoutInvokingExceptionMessageAccessors(SnapshotPath path) throws Exception {
        FailingHealthSink delegate = new FailingHealthSink();
        EventSink output = path == SnapshotPath.DIRECT_RUNTIME ? delegate
                : new AsyncSink("probe", delegate, 16, new OverflowPolicy(null), Duration.ofSeconds(2));
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("probe"), List.of()),
                Map.of(), Map.of("probe", output), Map.of()));
        var executor = Executors.newSingleThreadExecutor();
        Callable<ComponentHealth> query = () -> path == SnapshotPath.ASYNC_OUTPUT
                ? ((AsyncSink) output).health("probe")
                : runtime.health().components().stream().filter(value -> value.name().equals("probe"))
                        .findFirst().orElseThrow();
        try {
            var pending = executor.submit(query);
            assertTrue(delegate.healthCalled.await(1, TimeUnit.SECONDS));
            ComponentHealth failed = pending.get(1, TimeUnit.SECONDS);
            String failureKey = path == SnapshotPath.DIRECT_RUNTIME ? "health_failure" : "delegate_health_failure";
            assertEquals(HealthStatus.FAILED, failed.status());
            assertEquals(BlockingMessageFailure.class.getName(), failed.details().get(failureKey));
            assertEquals(0, delegate.failure.messageCalls.get());
            assertEquals(1L, delegate.failure.release.getCount());

            runtime.logger("probe").info("still delivered");
            assertTrue(delegate.delivered.await(1, TimeUnit.SECONDS));
            delegate.failing = false;
            ComponentHealth recovered = executor.submit(query).get(1, TimeUnit.SECONDS);
            assertEquals(HealthStatus.HEALTHY, recovered.status());
            assertNull(recovered.details().get(failureKey));
            assertEquals(0, delegate.failure.messageCalls.get());
        } finally {
            delegate.failure.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            runtime.close();
        }
    }

    private static final class FailingHealthSink implements EventSink, HealthContributor {
        private final BlockingMessageFailure failure = new BlockingMessageFailure();
        private final CountDownLatch healthCalled = new CountDownLatch(1);
        private final CountDownLatch delivered = new CountDownLatch(1);
        private volatile boolean failing = true;

        @Override
        public void accept(LogEvent event) {
            delivered.countDown();
        }

        @Override
        public ComponentHealth health(String componentName) {
            healthCalled.countDown();
            if (failing) throw failure;
            return ComponentHealth.healthy(componentName, "probe");
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
