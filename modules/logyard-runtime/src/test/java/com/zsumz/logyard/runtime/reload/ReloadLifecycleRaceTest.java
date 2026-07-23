package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReloadLifecycleRaceTest {
    @Test
    void reloadFlushPublicationAndCloseCompleteUnderRepeatedRaces() throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            runRace(iteration);
        }
    }

    private static void runRace(int iteration) throws Exception {
        Path directory = Files.createTempDirectory("logyard-lifecycle-race-");
        Path source = directory.resolve("logyard.toml");
        Path output = directory.resolve("events.jsonl");
        Files.writeString(source, config(output, "info"), StandardCharsets.UTF_8);
        RuntimeBundle bundle = LogyardBootstrap.start(source);
        Files.writeString(source, config(output, "error"), StandardCharsets.UTF_8);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            tasks.add(executor.submit(() -> repeat(start, 100, bundle::reloadNow)));
            tasks.add(executor.submit(() -> repeat(start, 500, bundle.runtime()::flush)));
            tasks.add(executor.submit(() -> repeat(start, 1_000, () -> bundle.runtime().logger("race." + iteration).info("event {}", iteration))));
            tasks.add(executor.submit(() -> {
                await(start);
                bundle.close();
            }));
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
            assertTrue(executor.shutdownNow().isEmpty());
        } finally {
            bundle.close();
            executor.shutdownNow();
        }
    }

    private static void repeat(CountDownLatch start, int count, Runnable action) {
        await(start);
        for (int index = 0; index < count; index++) {
            action.run();
        }
    }

    private static void await(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static String config(Path output, String level) {
        return """
                schema = 1
                [runtime]
                shutdown_timeout = "1s"
                internal_status = "off"
                [delivery]
                mode = "sync"
                capacity = 16
                [context]
                [loggers]
                root = { level = "%s", outputs = ["json"] }
                [outputs.json]
                type = "file"
                path = "%s"
                append = true
                buffer = "4KiB"
                flush = "0s"
                """.formatted(level, output.toString().replace("\\", "\\\\"));
    }
}
