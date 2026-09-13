package com.logyard4j.logyard.output.json.encoding;

import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.api.event.LogEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.logyard4j.logyard.output.json.encoding.JsonUtf8ParityTest.assertBytes;
import static com.logyard4j.logyard.output.json.encoding.JsonUtf8ParityTest.event;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonUtf8StorageTest {
    private static final ResourceAttributes RESOURCE = new ResourceAttributes(Map.of());
    private static final JsonProfile PROFILE = JsonProfile.named("logyard");

    @Test
    void exactCharacterAndByteCeilingsDoNotOverallocate() {
        JsonUtf8Buffer buffer = new JsonUtf8Buffer();
        for (char character : new char[] {'x', 'é', '界'}) {
            buffer.reset();
            for (int index = 0; index < JsonOutputLimits.MAX_RECORD_CHARACTERS; index++) {
                buffer.append(character);
            }
            int width = character == 'x' ? 1 : character == 'é' ? 2 : 3;
            assertEquals(width * JsonOutputLimits.MAX_RECORD_CHARACTERS, buffer.length());
            assertTrue(buffer.capacity() <= JsonUtf8Buffer.MAX_RECORD_BYTES);
            assertThrows(JsonLimitExceeded.class, () -> buffer.append('x'));
        }
        buffer.reset();
        assertTrue(buffer.capacity() <= 4_096);
    }

    @Test
    void largeRecordReleasesItsBufferBeforeManySmallRecords() {
        JsonEncoder text = new JsonEncoder(RESOURCE, PROFILE);
        AtomicReference<String> expected = new AtomicReference<>();
        AtomicInteger records = new AtomicInteger();
        AtomicInteger largest = new AtomicInteger();
        JsonUtf8Output output = new JsonUtf8Output(RESOURCE, PROFILE, (bytes, length) -> {
            assertBytes(expected.get(), bytes, length);
            largest.accumulateAndGet(length, Math::max);
            records.incrementAndGet();
        });
        LogEvent large = event("large", AttributeSet.of("payload", "界".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS)), null);
        expected.set(text.encode(large));
        output.accept(large);
        assertTrue(largest.get() > 4_096);
        assertTrue(output.retainedCapacity() <= 4_096);
        for (int index = 0; index < 1_000; index++) {
            LogEvent small = event("small " + index, AttributeSet.EMPTY, null);
            expected.set(text.encode(small));
            output.accept(small);
            assertTrue(output.retainedCapacity() <= 4_096);
        }
        assertEquals(1_001, records.get());
    }

    @Test
    void deliveryFailureAndReentrantCallsCannotCorruptLaterRecords() {
        AtomicReference<JsonUtf8Output> reference = new AtomicReference<>();
        AtomicInteger records = new AtomicInteger();
        LogEvent value = event("stable", AttributeSet.EMPTY, null);
        String expected = new JsonEncoder(RESOURCE, PROFILE).encode(value);
        IllegalStateException failure = new IllegalStateException("injected transport failure");
        JsonUtf8Output output = new JsonUtf8Output(RESOURCE, PROFILE, (bytes, length) -> {
            assertThrows(IllegalStateException.class, () -> reference.get().accept(value));
            assertBytes(expected, bytes, length);
            if (records.incrementAndGet() == 1) throw failure;
        });
        reference.set(output);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> output.accept(value)));
        output.accept(value);
        assertEquals(2, records.get());
    }

    @Test
    void ecsScratchTextIsReleasedEvenWhenLaterLabelsAreStrings() {
        JsonUtf8Buffer buffer = new JsonUtf8Buffer();
        JsonWriter tokens = new JsonWriter(buffer);
        JsonAttributesWriter attributes = new JsonAttributesWriter(tokens, JsonProfile.named("ecs"));
        attributes.write(true, AttributeSet.of("large", List.of("x".repeat(10_000))));
        assertTrue(attributes.retainedLabelCapacity() > 4_096);
        attributes.reset();
        assertTrue(attributes.retainedLabelCapacity() <= 4_096);
        for (int index = 0; index < 100; index++) {
            tokens.reset();
            attributes.write(true, AttributeSet.of("small", "value"));
            attributes.reset();
            assertTrue(attributes.retainedLabelCapacity() <= 4_096);
        }
    }
}
