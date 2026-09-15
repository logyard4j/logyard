package com.logyard4j.logyard.runtime.bootstrap;

import com.logyard4j.logyard.api.diagnostics.ComponentHealth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProductionErrorOutputTest {
    @TempDir
    Path directory;

    @Test
    void errorOnlyOutputContinuesWhileInfoFillsStalledConsole() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PrintStream previous = System.err;
        PrintStream stalled = new PrintStream(new OutputStream() {
            @Override
            public void write(int value) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("stalled console was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        });
        RuntimeBundle bundle = null;
        try {
            System.setErr(stalled);
            Path errors = directory.resolve("errors.jsonl");
            String config = """
                    schema = 1
                    [loggers]
                    root = { level = "info", outputs = ["console", "errors"] }
                    [outputs.console]
                    type = "console"
                    stream = "stderr"
                    color = { mode = "never" }
                    [outputs.errors]
                    type = "file"
                    path = "%s"
                    min_level = "error"
                    delivery = { mode = "async", capacity = 1024 }
                    flush = "1s"
                    fsync = true
                    """.formatted(errors.toString().replace("\\", "\\\\"));
            bundle = LogyardBootstrap.start(LogyardConfigurationSource.text("production errors", config, directory));
            var logger = bundle.runtime().logger("production.Service");
            logger.info("stall the console");
            assertTrue(entered.await(2, TimeUnit.SECONDS), "console worker did not stall");
            for (int index = 0; index < 300; index++) {
                logger.info("fill only the console queue");
            }
            logger.error("error survives console saturation");

            awaitText(errors, "error survives console saturation", Duration.ofSeconds(5));
            ComponentHealth console = output(bundle, "console");
            ComponentHealth errorOutput = output(bundle, "errors");
            assertTrue(console.metrics().get("dropped_total") > 0L);
            assertEquals(1024L, errorOutput.metrics().get("capacity"));
            assertEquals(0L, errorOutput.metrics().get("dropped_total"));
            assertEquals(1L, errorOutput.metrics().get("delivered_total"));
        } finally {
            release.countDown();
            try {
                if (bundle != null) bundle.close();
            } finally {
                System.setErr(previous);
                stalled.close();
            }
        }
    }

    private static ComponentHealth output(RuntimeBundle bundle, String name) {
        return bundle.runtime().health().components().stream()
                .filter(component -> component.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static void awaitText(Path file, String expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(file) && Files.readString(file).contains(expected)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("ERROR-only file did not receive " + expected);
    }
}
