package com.logyard4j.logyard.runtime.bootstrap;

import com.logyard4j.logyard.api.diagnostics.ComponentHealth;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProductionDefaultsTest {
    @Test
    void stalledConsoleBoundsPublishingAndShutdownWithoutCallerOutput() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> outputWorker = new AtomicReference<>();
        PrintStream previous = System.err;
        PrintStream stalled = new PrintStream(new OutputStream() {
            @Override
            public void write(int value) {
                outputWorker.compareAndSet(null, Thread.currentThread());
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("stalled output was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        });
        RuntimeBundle bundle = null;
        Thread caller = null;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            System.setErr(stalled);
            bundle = LogyardBootstrap.start(LogyardConfigurationSource.text("stalled console", """
                    schema = 1
                    [runtime]
                    shutdown_timeout = "50ms"
                    [loggers]
                    root = { level = "info", outputs = ["console"] }
                    [outputs.console]
                    type = "console"
                    stream = "stderr"
                    color = { mode = "never" }
                    """, Path.of(".")));
            RuntimeBundle owned = bundle;
            var logger = owned.runtime().logger("test.Service");
            logger.info("first event stalls the output worker");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            caller = new Thread(() -> {
                try {
                    for (int index = 0; index < 256; index++) {
                        logger.info("fill the output queue");
                    }
                    logger.warn("warning after saturation");
                    logger.error("error after saturation");
                    ComponentHealth output = owned.runtime().health().components().stream()
                            .filter(component -> component.name().equals("console"))
                            .findFirst().orElseThrow();
                    assertEquals(256L, output.metrics().get("capacity"));
                    assertEquals(2L, output.metrics().get("dropped_total"));
                    owned.close();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            }, "production-default-test");
            caller.setDaemon(true);
            caller.start();
            caller.join(2_000);
            assertFalse(caller.isAlive(), "logging or shutdown waited for the stalled console");
            assertNull(failure.get());
        } finally {
            release.countDown();
            if (caller != null) {
                caller.join(2_000);
            }
            if (outputWorker.get() != null) {
                outputWorker.get().join(2_000);
            }
            System.setErr(previous);
            if (bundle != null) {
                bundle.close();
            }
            stalled.close();
        }
    }
}
