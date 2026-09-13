package com.logyard4j.logyard.output.json.encoding;

import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.logyard4j.logyard.output.json.encoding.JsonUtf8ParityTest.event;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonUtf8ConcurrencyTest {
    @Test
    void concurrentPublishersProduceCompleteUniqueRecords() throws Exception {
        JsonEncoder text = new JsonEncoder(new ResourceAttributes(Map.of()));
        Set<String> records = new HashSet<>();
        Consumer<LogEvent> output = text.utf8Records((bytes, length) -> {
            String record = new String(bytes, 0, length, StandardCharsets.UTF_8);
            JsonSyntaxValidator.requireValid(record);
            assertTrue(records.add(record), "duplicate record");
        });
        Set<String> expected = new HashSet<>();
        try (var publishers = Executors.newFixedThreadPool(4)) {
            ArrayList<Future<?>> pending = new ArrayList<>();
            for (int producer = 0; producer < 4; producer++) {
                int id = producer;
                for (int sequence = 0; sequence < 200; sequence++) {
                    expected.add(text.encode(event(id + ":" + sequence + " 界😀\n", AttributeSet.EMPTY, null)));
                }
                pending.add(publishers.submit(() -> {
                    for (int sequence = 0; sequence < 200; sequence++) {
                        output.accept(event(id + ":" + sequence + " 界😀\n", AttributeSet.EMPTY, null));
                    }
                }));
            }
            for (Future<?> publisher : pending) publisher.get(5, TimeUnit.SECONDS);
        }
        assertEquals(expected, records);
        assertEquals(800, records.size());
    }

    @Test
    void stalledOutputDoesNotHoldAnotherOutputsEncoderOrThePublicTextEncoder() throws Exception {
        JsonEncoder shared = new JsonEncoder(new ResourceAttributes(Map.of()));
        LogEvent value = event("independent", AttributeSet.EMPTY, null);
        String expected = shared.encode(value);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Consumer<LogEvent> blocked = shared.utf8Records((bytes, length) -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                JsonUtf8ParityTest.assertBytes(expected, bytes, length);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        });
        Consumer<LogEvent> independent = shared.utf8Records((bytes, length) ->
                JsonUtf8ParityTest.assertBytes(expected, bytes, length));
        try (var publishers = Executors.newFixedThreadPool(2)) {
            Future<?> stalled = publishers.submit(() -> blocked.accept(value));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                publishers.submit(() -> {
                    independent.accept(value);
                    assertEquals(expected, shared.encode(value));
                }).get(2, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            stalled.get(2, TimeUnit.SECONDS);
        }
    }
}
