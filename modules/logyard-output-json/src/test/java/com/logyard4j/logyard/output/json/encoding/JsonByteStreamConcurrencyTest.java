package com.logyard4j.logyard.output.json.encoding;

import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.output.json.stream.JsonLinesSink;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonByteStreamConcurrencyTest {
    @Test
    void concurrentOutputsSharingAnEncoderKeepIndependentCompleteRecords() throws Exception {
        JsonEncoder encoder = new JsonEncoder(ResourceAttributes.service("test", "test", "1"));
        ByteArrayOutputStream firstBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream secondBytes = new ByteArrayOutputStream();
        List<String> expected = new ArrayList<>();
        var executor = Executors.newFixedThreadPool(4);
        try (JsonLinesSink first = JsonLinesSink.bytes(firstBytes, encoder, Duration.ofMinutes(1), false);
                JsonLinesSink second = JsonLinesSink.bytes(secondBytes, encoder, Duration.ofMinutes(1), false)) {
            try {
                List<Future<?>> calls = new ArrayList<>();
                for (int sequence = 0; sequence < 256; sequence++) {
                    var event = JsonUtf8ParityTest.event("record " + sequence + " 界😀\n",
                            AttributeSet.of("sequence", sequence), null);
                    expected.add(encoder.encode(event));
                    calls.add(executor.submit(() -> {
                        first.accept(event);
                        second.accept(event);
                    }));
                }
                for (Future<?> call : calls) call.get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(expected.stream().sorted().toList(), records(firstBytes));
        assertEquals(expected.stream().sorted().toList(), records(secondBytes));
    }

    @Test
    void stalledOutputDoesNotBlockAnotherOutputOrLoseItsActiveRecord() throws Exception {
        JsonEncoder encoder = new JsonEncoder(ResourceAttributes.service("test", "test", "1"));
        ByteArrayOutputStream firstBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream secondBytes = new ByteArrayOutputStream();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OutputStream blocked = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                write(new byte[] {(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] value, int offset, int length) throws IOException {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IOException(failure);
                }
                firstBytes.write(value, offset, length);
            }
        };
        var large = JsonUtf8ParityTest.event("界".repeat(8_000), AttributeSet.of("sequence", 1), null);
        var small = JsonUtf8ParityTest.event("second", AttributeSet.of("sequence", 2), null);
        var executor = Executors.newFixedThreadPool(2);
        try (JsonLinesSink first = JsonLinesSink.bytes(blocked, encoder, Duration.ofMinutes(1), false);
                JsonLinesSink second = JsonLinesSink.bytes(secondBytes, encoder, Duration.ofMinutes(1), false)) {
            try {
                Future<?> stalled = executor.submit(() -> {
                    first.accept(large);
                    first.flush();
                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                executor.submit(() -> {
                    second.accept(small);
                    second.flush();
                }).get(1, TimeUnit.SECONDS);
                assertEquals(List.of(encoder.encode(small)), records(secondBytes));
                assertEquals(1L, release.getCount());
                release.countDown();
                stalled.get(3, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(List.of(encoder.encode(large)), records(firstBytes));
    }

    private static List<String> records(ByteArrayOutputStream bytes) throws Exception {
        String decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        assertTrue(decoded.endsWith("\n"));
        List<String> records = decoded.lines().sorted().toList();
        records.forEach(JsonSyntaxValidator::requireValid);
        return records;
    }
}
