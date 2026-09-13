package com.logyard4j.spring.boot.autoconfigure;

import com.logyard4j.api.Level;
import com.logyard4j.api.Logyard;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.delivery.async.AsyncSink;
import com.logyard4j.core.delivery.async.OverflowPolicy;
import com.logyard4j.core.routing.RouteDefinition;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.core.runtime.RuntimePlan;
import com.logyard4j.output.json.stream.JsonLinesSink;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LogyardBlockedOutputHealthTest {
    enum Operation {
        WRITE, FLUSH, SCHEDULED_FLUSH, CLOSE
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void actuatorHealthReturnsWhileDirectOutputIsBlocked(Operation operation) throws Exception {
        verify(operation, false);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void actuatorHealthReturnsWhileAsyncOutputIsBlocked(Operation operation) throws Exception {
        verify(operation, true);
    }

    private static void verify(Operation operation, boolean async) throws Exception {
        GateWriter writer = new GateWriter(operation);
        JsonLinesSink sink = new JsonLinesSink(writer, event -> "{}", Duration.ofMillis(1), true);
        EventSink output = async
                ? new AsyncSink("blocked", sink, 16, new OverflowPolicy(null), Duration.ofSeconds(2))
                : sink;
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("blocked"), List.of()),
                Map.of(), Map.of("blocked", output), Map.of()));
        var executor = Executors.newFixedThreadPool(2);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // Creating a Spring context can initialize the installed SLF4J provider first.
            Logyard.shutdown();
            Logyard.initialize(runtime);
            context.register(LogyardActuatorAutoConfiguration.class);
            context.refresh();
            HealthIndicator indicator = context.getBean("logyard", HealthIndicator.class);
            var io = executor.submit(() -> {
                switch (operation) {
                    case WRITE, SCHEDULED_FLUSH -> runtime.logger("probe").info("record");
                    case FLUSH -> output.flush();
                    case CLOSE -> output.close();
                }
            });
            assertTrue(writer.entered.await(2, TimeUnit.SECONDS));
            var health = executor.submit(indicator::health).get(1, TimeUnit.SECONDS);
            assertEquals(operation == Operation.CLOSE ? Status.DOWN : Status.UP, health.getStatus());
            var view = new LogyardRuntimeHealth(runtime);
            executor.submit(view::snapshot).get(1, TimeUnit.SECONDS);
            assertEquals(1L, writer.release.getCount());
            writer.release.countDown();
            io.get(3, TimeUnit.SECONDS);
        } finally {
            writer.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            Logyard.shutdownIfCurrent(runtime);
            runtime.close();
        }
    }

    private static final class GateWriter extends Writer {
        private final Operation operation;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private GateWriter(Operation operation) {
            this.operation = operation;
        }

        @Override
        public void write(char[] value, int offset, int length) {
            block(Operation.WRITE);
        }

        @Override
        public void flush() {
            block(operation == Operation.SCHEDULED_FLUSH ? Operation.SCHEDULED_FLUSH : Operation.FLUSH);
        }

        @Override
        public void close() {
            block(Operation.CLOSE);
        }

        private void block(Operation current) {
            if (operation != current) {
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
    }
}
