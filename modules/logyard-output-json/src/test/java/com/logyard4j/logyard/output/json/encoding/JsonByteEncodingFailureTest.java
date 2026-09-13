package com.logyard4j.logyard.output.json.encoding;

import com.logyard4j.logyard.api.diagnostics.HealthStatus;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.output.json.stream.JsonLinesSink;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JsonByteEncodingFailureTest {
    @Test
    void rejectedEventDoesNotFailTheTransportOrContaminateTheNextRecord() {
        JsonProfile profile = JsonProfile.custom("collision", "logyard", Map.of(), List.of(),
                new JsonAttributeTransform(JsonAttributeTransform.Mode.NESTED, null,
                        List.of(), List.of(), Map.of("first", "second")));
        JsonEncoder encoder = new JsonEncoder(new ResourceAttributes(Map.of()), profile);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        var valid = JsonUtf8ParityTest.event("valid", AttributeSet.of("first", 1), null);
        try (JsonLinesSink sink = JsonLinesSink.bytes(bytes, encoder, Duration.ZERO, false)) {
            assertThrows(IllegalArgumentException.class, () -> sink.accept(JsonUtf8ParityTest.event("invalid",
                    AttributeSet.builder().put("first", 1).put("second", 2).build(), null)));
            assertEquals(HealthStatus.HEALTHY, sink.health("json").status());
            assertEquals(0, bytes.size());
            sink.accept(valid);
        }
        assertEquals(encoder.encode(valid) + '\n', bytes.toString(StandardCharsets.UTF_8));
    }
}
