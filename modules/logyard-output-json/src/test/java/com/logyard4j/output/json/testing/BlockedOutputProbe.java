package com.logyard4j.output.json.testing;

import com.logyard4j.api.Level;
import com.logyard4j.api.diagnostics.ComponentHealth;
import com.logyard4j.api.diagnostics.HealthStatus;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.diagnostics.HealthContributor;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.delivery.async.AsyncSink;
import com.logyard4j.core.delivery.async.OverflowPolicy;
import com.logyard4j.core.routing.RouteDefinition;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.core.runtime.RuntimePlan;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Holds real output I/O until direct, wrapped, and runtime health have all returned. */
public final class BlockedOutputProbe {
    public enum Operation {
        WRITE, FLUSH, SCHEDULED_FLUSH, CLOSE
    }

    private final Operation operation;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    public BlockedOutputProbe(Operation operation) {
        this.operation = operation;
    }

    public void visit(Operation current) {
        if (current != operation && !(current == Operation.FLUSH && operation == Operation.SCHEDULED_FLUSH)) {
            return;
        }
        entered.countDown();
        boolean interrupted = false;
        while (true) {
            try {
                release.await();
                break;
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public void verify(EventSink sink, ManualFlushScheduler scheduler, boolean async) throws Exception {
        HealthContributor health = (HealthContributor) sink;
        EventSink output = async
                ? new AsyncSink("blocked", sink, 16, new OverflowPolicy(null), Duration.ofSeconds(2))
                : sink;
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("blocked"), List.of()),
                Map.of(), Map.of("blocked", output), Map.of()));
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> io = executor.submit(() -> {
                switch (operation) {
                    case WRITE -> runtime.logger("probe").info("record");
                    case FLUSH -> output.flush();
                    case CLOSE -> output.close();
                    case SCHEDULED_FLUSH -> {
                        sink.accept(event());
                        scheduler.runNext();
                    }
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS), "output never entered " + operation);
            ComponentHealth snapshot = executor.submit(() -> health.health("blocked")).get(1, TimeUnit.SECONDS);
            assertEquals(operation.name().toLowerCase(java.util.Locale.ROOT), snapshot.details().get("io_operation"));
            assertEquals(operation == Operation.CLOSE ? HealthStatus.STOPPING : HealthStatus.HEALTHY, snapshot.status());
            var runtimeHealth = executor.submit(runtime::health).get(1, TimeUnit.SECONDS);
            ComponentHealth outputHealth = runtimeHealth.components().stream()
                    .filter(component -> component.name().equals("blocked")).findFirst().orElseThrow();
            assertEquals(snapshot.details().get("io_operation"),
                    outputHealth.details().get(async ? "delegate_io_operation" : "io_operation"));
            assertEquals(1L, release.getCount(), "health required released I/O");
            release.countDown();
            io.get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            runtime.close();
        }
        assertEquals(HealthStatus.STOPPED, health.health("blocked").status());
        assertEquals("idle", health.health("blocked").details().get("io_operation"));
    }

    private static LogEvent event() {
        return new LogEvent(0L, 0L, Level.INFO, "probe", "test", "record", null, AttributeSet.EMPTY, null, 1L, "test");
    }
}
