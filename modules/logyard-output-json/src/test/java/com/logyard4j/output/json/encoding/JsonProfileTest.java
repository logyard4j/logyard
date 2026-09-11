package com.logyard4j.output.json.encoding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class JsonProfileTest {
    @Test
    void appliesTopLevelAndAttributeTransforms() {
        JsonProfile profile = JsonProfile.custom(
                "audit",
                "compact",
                Map.of("logger", "source"),
                List.of("message_template"),
                new JsonAttributeTransform(
                        JsonAttributeTransform.Mode.FLATTEN,
                        "field.",
                        List.of("request.id", "secret"),
                        List.of("secret"),
                        Map.of("request.id", "request_id")));
        JsonEncoder encoder = new JsonEncoder(
                ResourceAttributes.service("orders", "test", "1"), profile);

        String json = encoder.encode(event());

        assertTrue(json.contains("\"source\":\"orders.Service\""));
        assertTrue(json.contains("\"field.request_id\":\"req-7\""));
        assertFalse(json.contains("\"secret\""));
        assertFalse(json.contains("\"template\""));
        assertFalse(json.contains("\"fields\":{"));
    }

    @Test
    void rejectsTopLevelAndFlattenedFieldCollisions() {
        assertThrows(IllegalArgumentException.class, () -> JsonProfile.custom(
                "collision",
                "logyard",
                Map.of("body", "logger"),
                List.of(),
                JsonAttributeTransform.nested()));
        assertThrows(IllegalArgumentException.class, () -> JsonProfile.custom(
                "flatten-collision",
                "logyard",
                Map.of("body", "field.message"),
                List.of(),
                new JsonAttributeTransform(
                        JsonAttributeTransform.Mode.FLATTEN,
                        "field.",
                        List.of(),
                        List.of(),
                        Map.of())));
    }

    @Test
    void ecsProfileUsesStableLiteralDottedFieldNames() {
        String json = new JsonEncoder(
                ResourceAttributes.service("orders", "test", "1"),
                JsonProfile.named("ecs"))
                .encode(event());

        assertTrue(json.contains("\"@timestamp\":"));
        assertTrue(json.contains("\"log.level\":\"INFO\""));
        assertTrue(json.contains("\"message\":\"accepted 7\""));
    }

    private static LogEvent event() {
        return new LogEvent(
                0L,
                12_000_000L,
                Level.INFO,
                "orders.Service",
                "order.accepted",
                "accepted {}",
                new Object[] {7},
                AttributeSet.builder()
                        .put("request.id", "req-7")
                        .put("secret", "hidden")
                        .put("other", true)
                        .build(),
                null,
                4L,
                "main");
    }
}
