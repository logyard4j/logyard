package com.logyard4j.output.json.encoding;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.CaptureLimits;
import com.logyard4j.api.event.LogEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonUtf8ParityTest {
    @Test
    void randomizedUtf16AndTypedValuesMatchTextEncodingAcrossProfiles() {
        ResourceAttributes resource = ResourceAttributes.service("orders", "test", "1");
        SplittableRandom random = new SplittableRandom(0x555446384a534f4eL);
        for (JsonProfile profile : profiles()) {
            JsonEncoder text = new JsonEncoder(resource, profile);
            for (int sample = 0; sample < 150; sample++) {
                StringBuilder content = new StringBuilder("\u0000\n\"\\é界😀\ud800\udc00");
                for (int index = 0; index < sample; index++) {
                    content.append((char) random.nextInt(Character.MAX_VALUE + 1));
                }
                LogEvent event = event(content.toString(), AttributeSet.builder()
                        .put("nested", List.of(Map.of("text", content.toString())))
                        .put("nil", null).put("minimum", Long.MIN_VALUE).put("maximum", Long.MAX_VALUE)
                        .put("amount", new BigDecimal("1E+1000000")).put("nonfinite", Double.NaN)
                        .put("drop", "private").build(), new IllegalStateException(content.toString()));
                assertRecord(text, event);
            }
        }
    }

    @Test
    void oversizedRecordsKeepTheExactProfileFallback() {
        String controls = "\u0000".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS);
        ResourceAttributes resource = new ResourceAttributes(Map.of("noisy", controls));
        LogEvent event = event("body-secret", AttributeSet.of("payload", controls),
                new IllegalStateException("\u0000".repeat(CaptureLimits.MAX_EVENT_EXCEPTION_MESSAGE_CHARS)));
        for (JsonProfile profile : profiles()) {
            JsonEncoder encoder = new JsonEncoder(resource, profile);
            assertTrue(encoder.encode(event).contains("\"logyard.output.truncated\":true"));
            assertRecord(encoder, event);
        }
    }

    @Test
    void encodingFailureEmitsNothingAndTheNextRecordStartsCleanly() {
        JsonProfile profile = JsonProfile.custom("collision", "logyard", Map.of(), List.of(),
                new JsonAttributeTransform(JsonAttributeTransform.Mode.NESTED, null,
                        List.of(), List.of(), Map.of("first", "second")));
        AtomicInteger records = new AtomicInteger();
        JsonEncoder encoder = new JsonEncoder(new ResourceAttributes(Map.of()), profile);
        LogEvent valid = event("valid", AttributeSet.of("first", 1), null);
        Consumer<LogEvent> output = encoder.utf8Records((bytes, length) -> {
            assertBytes(encoder.encode(valid), bytes, length);
            records.incrementAndGet();
        });
        assertThrows(IllegalArgumentException.class, () -> output.accept(event("invalid",
                AttributeSet.builder().put("first", 1).put("second", 2).build(), null)));
        assertEquals(0, records.get());
        output.accept(valid);
        assertEquals(1, records.get());
    }

    static LogEvent event(String message, AttributeSet attributes, Throwable failure) {
        return new LogEvent(0, 1, Level.ERROR, "logger", "event.identity", message, null,
                attributes, failure, 1, "main");
    }

    static void assertBytes(String expected, byte[] bytes, int length) {
        assertTrue(length <= JsonUtf8Buffer.MAX_RECORD_BYTES);
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), Arrays.copyOf(bytes, length));
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, length)).toString();
            assertEquals(expected, decoded);
            JsonSyntaxValidator.requireValid(decoded);
        } catch (CharacterCodingException failure) {
            throw new AssertionError("record is not valid UTF-8", failure);
        }
    }

    private static void assertRecord(JsonEncoder text, LogEvent event) {
        String expected = text.encode(event);
        text.utf8Records((bytes, length) -> assertBytes(expected, bytes, length)).accept(event);
    }

    private static List<JsonProfile> profiles() {
        return List.of(JsonProfile.named("logyard"), JsonProfile.named("ecs"), JsonProfile.named("compact"),
                JsonProfile.custom("private", "logyard", Map.of("timestamp", "time"), List.of("body", "logger"),
                        new JsonAttributeTransform(JsonAttributeTransform.Mode.FLATTEN, "field.",
                                List.of(), List.of("drop"), Map.of("nested", "renamed"))));
    }
}
