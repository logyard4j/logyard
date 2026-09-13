package com.logyard4j.logyard.output.json.encoding;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.output.json.file.JsonFileSink;
import com.logyard4j.logyard.output.json.stream.JsonLinesSink;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonEncoderConcurrencyTest {
    private static final int PRODUCERS = 12;
    private static final int EVENTS_PER_PRODUCER = 100;
    private static final Pattern EVENT_ID = Pattern.compile("\"event.id\":(\\d+)");

    @Test
    void directStreamSinkProducesExactValidRecordsUnderConcurrentEncoding() throws Exception {
        StringWriter output = new StringWriter();
        JsonEncoder encoder = new JsonEncoder(ResourceAttributes.service("concurrency", "test", "1"));
        try (JsonLinesSink sink = new JsonLinesSink(output, encoder, Duration.ofMinutes(1L), false)) {
            publishConcurrently(sink);
        }
        assertExactValidRecords(output.toString());
    }

    @Test
    void directFileSinkProducesExactValidRecordsUnderConcurrentEncoding() throws Exception {
        Path output = Files.createTempDirectory("logyard-json-concurrency-").resolve("events.jsonl");
        JsonEncoder encoder = new JsonEncoder(ResourceAttributes.service("concurrency", "test", "1"));
        try (JsonFileSink sink = new JsonFileSink(output, encoder, 16_384, Duration.ofMinutes(1L), false, null)) {
            publishConcurrently(sink);
        }
        assertExactValidRecords(Files.readString(output));
    }

    private static void publishConcurrently(EventSink sink) throws Exception {
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
                        int eventId = producerId * EVENTS_PER_PRODUCER + sequence;
                        sink.accept(event(eventId));
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
            JsonSyntaxValidator.requireValid(line);
            Matcher identifier = EVENT_ID.matcher(line);
            assertTrue(identifier.find(), () -> "event identifier missing from " + line);
            assertTrue(identifiers.add(Integer.parseInt(identifier.group(1))), "duplicate event identifier");
        }
        assertEquals(PRODUCERS * EVENTS_PER_PRODUCER, identifiers.size());
    }

    private static LogEvent event(int identifier) {
        return new LogEvent(
                identifier,
                identifier,
                Level.INFO,
                "test.Concurrent",
                "concurrent.event",
                "event {}",
                new Object[] {identifier},
                AttributeSet.of("event.id", identifier),
                null,
                Thread.currentThread().threadId(),
                Thread.currentThread().getName());
    }
}
