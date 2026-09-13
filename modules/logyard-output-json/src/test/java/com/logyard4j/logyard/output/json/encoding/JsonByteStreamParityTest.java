package com.logyard4j.logyard.output.json.encoding;

import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.output.json.stream.JsonLinesSink;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonByteStreamParityTest {
    @ParameterizedTest
    @ValueSource(strings = {"logyard", "compact", "ecs"})
    void largeThenSmallRecordsPreserveUnicodeFramingAndProfileOutput(String profile) throws Exception {
        JsonEncoder encoder = new JsonEncoder(ResourceAttributes.service("orders", "test", "1"), JsonProfile.named(profile));
        List<LogEvent> events = new ArrayList<>();
        for (int index = 0; index <= 100; index++) {
            String message = index == 0 ? "界".repeat(8_000) : "record " + index + " é界😀\n\ud800-\udc00";
            events.add(JsonUtf8ParityTest.event(message, AttributeSet.builder().put("sequence", index)
                    .put("nested", List.of(Map.of("special", "\u0000\n\"\\é界😀\ud800-\udc00"))).build(), null));
        }
        assertRecords(encoder, events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"logyard", "compact", "ecs"})
    void cappedRecordKeepsTheExactFallbackAndTheNextRecordIsComplete(String profile) throws Exception {
        String controls = "\u0000".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS);
        JsonEncoder encoder = new JsonEncoder(new ResourceAttributes(Map.of("noisy", controls)), JsonProfile.named(profile));
        LogEvent oversized = JsonUtf8ParityTest.event("body-secret", AttributeSet.of("payload", controls),
                new IllegalStateException("\u0000".repeat(CaptureLimits.MAX_EVENT_EXCEPTION_MESSAGE_CHARS)));
        assertTrue(encoder.encode(oversized).contains("\"logyard.output.truncated\":true"));
        assertRecords(encoder, List.of(oversized, JsonUtf8ParityTest.event("small", AttributeSet.EMPTY, null)));
    }

    private static void assertRecords(JsonEncoder encoder, List<LogEvent> events) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StringBuilder expected = new StringBuilder();
        try (JsonLinesSink sink = JsonLinesSink.bytes(bytes, encoder, Duration.ofMinutes(1), false)) {
            for (LogEvent event : events) {
                expected.append(encoder.encode(event)).append('\n');
                sink.accept(event);
            }
        }
        String actual = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        assertEquals(expected.toString(), actual);
        List<String> records = actual.lines().toList();
        assertEquals(events.size(), records.size());
        records.forEach(JsonSyntaxValidator::requireValid);
    }
}
