package com.logyard4j.logyard.runtime.assembly;

import com.logyard4j.logyard.api.LogyardLogger;
import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.config.loading.LogyardConfigLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.logyard4j.logyard.runtime.testing.TomlTestStrings.escapeBasicString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeJsonConcurrencyTest {
    private static final int PRODUCERS = 12;
    private static final int EVENTS_PER_PRODUCER = 100;
    private static final Pattern EVENT_ID = Pattern.compile("\"event.id\":(\\d+)");

    @Test
    void synchronousRuntimeProducesExactValidJsonRecordsFromConcurrentProducers() throws Exception {
        Path output = Files.createTempDirectory("logyard-runtime-concurrency-").resolve("events.jsonl");
        LogyardConfig config = config(output);

        try (LogyardRuntime runtime = LogyardRuntimeFactory.create(config)) {
            publishConcurrently(runtime.logger("test.Concurrent"));
        }

        assertExactValidRecords(Files.readString(output));
    }

    private static void publishConcurrently(LogyardLogger logger) throws Exception {
        ExecutorService producers = Executors.newFixedThreadPool(PRODUCERS);
        CountDownLatch ready = new CountDownLatch(PRODUCERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> deliveries = new ArrayList<>();
        try {
            for (int producer = 0; producer < PRODUCERS; producer++) {
                int producerId = producer;
                deliveries.add(producers.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int sequence = 0; sequence < EVENTS_PER_PRODUCER; sequence++) {
                        int identifier = producerId * EVENTS_PER_PRODUCER + sequence;
                        logger.atInfo()
                                .event("concurrent.event")
                                .message("event {}")
                                .argument(identifier)
                                .add("event.id", identifier)
                                .log();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(2L, TimeUnit.SECONDS), "producer threads did not reach the start barrier");
            start.countDown();
            for (Future<?> delivery : deliveries) {
                delivery.get(10L, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            producers.shutdownNow();
            assertTrue(producers.awaitTermination(2L, TimeUnit.SECONDS));
        }
    }

    private static void assertExactValidRecords(String output) {
        List<String> lines = output.lines().toList();
        assertEquals(PRODUCERS * EVENTS_PER_PRODUCER, lines.size());
        Set<Integer> identifiers = new HashSet<>();
        for (String line : lines) {
            JsonRecordValidator.requireValid(line);
            Matcher identifier = EVENT_ID.matcher(line);
            assertTrue(identifier.find(), () -> "event identifier missing from " + line);
            assertTrue(identifiers.add(Integer.parseInt(identifier.group(1))), "duplicate event identifier");
        }
        assertEquals(PRODUCERS * EVENTS_PER_PRODUCER, identifiers.size());
    }

    private static LogyardConfig config(Path output) {
        return LogyardConfigLoader.parse(
                """
                        schema = 1
                        [service]
                        name = "concurrency"
                        [delivery]
                        mode = "sync"
                        [loggers]
                        root = { level = "info", outputs = ["json"] }
                        [outputs.json]
                        type = "file"
                        path = "%s"
                        append = false
                        flush = "1m"
                        """.formatted(escapeBasicString(output)),
                "runtime-json-concurrency.toml",
                Path.of("."),
                Map.of());
    }
}
