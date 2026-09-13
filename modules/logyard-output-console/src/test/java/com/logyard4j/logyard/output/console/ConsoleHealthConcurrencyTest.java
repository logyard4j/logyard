package com.logyard4j.logyard.output.console;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.diagnostics.ComponentHealth;
import com.logyard4j.logyard.api.diagnostics.HealthStatus;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.delivery.async.AsyncSink;
import com.logyard4j.logyard.core.delivery.async.OverflowPolicy;
import com.logyard4j.logyard.core.routing.RouteDefinition;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.logyard.core.runtime.RuntimePlan;
import com.logyard4j.logyard.output.console.style.BuiltInThemes;
import com.logyard4j.logyard.output.console.terminal.ColorCapability;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.OutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConsoleHealthConcurrencyTest {
    enum Operation { WRITE, FLUSH, CLOSE }

    @ParameterizedTest
    @CsvSource({"WRITE,false,false", "WRITE,true,false", "FLUSH,false,false", "FLUSH,true,false",
            "CLOSE,false,false", "CLOSE,true,false", "CLOSE,false,true", "CLOSE,true,true"})
    void healthReportsProgressWhileTransportIsBlocked(Operation operation, boolean async, boolean owned) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        OutputStream transport = new OutputStream() {
            @Override
            public void write(int value) {
                visit(Operation.WRITE);
            }

            @Override
            public void flush() {
                visit(operation == Operation.CLOSE && !owned ? Operation.CLOSE : Operation.FLUSH);
            }

            @Override
            public void close() {
                closes.incrementAndGet();
                visit(Operation.CLOSE);
            }

            private void visit(Operation current) {
                if (current != operation) return;
                entered.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        };
        ConsoleSink sink = new ConsoleSink(new PrintStream(transport), false, BuiltInThemes.ember(),
                ColorCapability.TRUECOLOR, ZoneOffset.UTC, true, owned);
        EventSink output = async ? new AsyncSink("console", sink, 16, new OverflowPolicy(null), Duration.ofSeconds(2)) : sink;
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("console"), List.of()),
                Map.of(), Map.of("console", output), Map.of()));
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> io = executor.submit(() -> {
                switch (operation) {
                    case WRITE -> runtime.logger("probe").info("record");
                    case FLUSH -> output.flush();
                    case CLOSE -> output.close();
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS), "transport did not enter " + operation);
            // Async cleanup flushes before closing; a borrowed stream stalls in that first flush.
            Operation delegateOperation = operation == Operation.CLOSE && async && !owned ? Operation.FLUSH : operation;
            HealthStatus expected = delegateOperation == Operation.CLOSE ? HealthStatus.STOPPING : HealthStatus.HEALTHY;
            ComponentHealth snapshot = executor.submit(() -> sink.health("console")).get(1, TimeUnit.SECONDS);
            assertEquals(expected, snapshot.status());
            assertEquals(delegateOperation.name().toLowerCase(Locale.ROOT), snapshot.details().get("io_operation"));
            var runtimeHealth = executor.submit(runtime::health).get(1, TimeUnit.SECONDS);
            ComponentHealth observed = runtimeHealth.components().stream()
                    .filter(component -> component.name().equals("console")).findFirst().orElseThrow();
            assertEquals(operation == Operation.CLOSE ? HealthStatus.STOPPING : expected, observed.status());
            assertEquals(snapshot.details().get("io_operation"),
                    observed.details().get(async ? "delegate_io_operation" : "io_operation"));
            assertEquals(1, release.getCount(), "health required released transport I/O");
            release.countDown();
            io.get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            runtime.close();
        }
        assertEquals(HealthStatus.STOPPED, sink.health("console").status());
        assertEquals("idle", sink.health("console").details().get("io_operation"));
        assertEquals(owned ? 1 : 0, closes.get(), "stream ownership or close idempotence changed");
    }
}
