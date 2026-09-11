package com.zsumz.logyard.output.json.encoding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class EcsProjectionTest {
    private static final ResourceAttributes RESOURCE = ResourceAttributes.service(
            "orders", "shop", "test", "1", "node-7", Map.of("region", "west"));

    @Test
    void projectsServiceThreadTimestampAndExceptionToTheirSchemaTypes() {
        String json = encode(JsonProfile.named("ecs"), AttributeSet.EMPTY);

        assertTrue(json.contains("\"ecs.version\":\"9.4.0\""));
        assertTrue(json.contains("\"service\":{\"name\":\"orders\",\"environment\":\"test\","
                + "\"version\":\"1\",\"node\":{\"name\":\"node-7\"}}"));
        assertTrue(json.contains("\"logyard.resource\":{\"service.namespace\":\"shop\",\"region\":\"west\"}"));
        assertTrue(json.contains("\"process.thread\":{\"id\":7,\"name\":\"worker\"}"));
        assertTrue(json.contains("\"event.created\":\"1970-01-01T00:00:00.012Z\""));
        assertTrue(json.contains("\"error\":{\"type\":\"java.lang.IllegalStateException\",\"message\":\"failed\","
                + "\"stack_trace\":\"java.lang.IllegalStateException: failed\\n\\tat orders.Service.run(Service.java:7)"));
        assertTrue(json.contains("Suppressed: java.lang.IllegalArgumentException: suppressed"));
        assertTrue(json.contains("Caused by: java.lang.RuntimeException: cause"));
        assertTrue(json.contains("\"logyard.message_template\":\"accepted {}\""));
        assertFalse(json.contains("\"message.template\":"));
        assertFalse(json.contains("\"service.name\":"));
        assertFalse(json.contains("\"stacktrace\":"));
    }

    @Test
    void emitsKeywordLabelsAndTopLevelTraceCorrelation() {
        String json = encode(JsonProfile.named("ecs"), AttributeSet.builder()
                .put("request", "r-1").put("count", 7).put("paid", true)
                .put("nested", Map.of("secret", "[REDACTED]"))
                .put("tags", List.of("one", "two"))
                .put("trace_id", "0123456789abcdef0123456789abcdef")
                .put("span_id", "0123456789abcdef").put("trace_flags", "01").build());

        assertTrue(json.contains("\"count\":\"7\""));
        assertTrue(json.contains("\"paid\":\"true\""));
        assertTrue(json.contains("\"nested\":\"{\\\"secret\\\":\\\"[REDACTED]\\\"}\""));
        assertTrue(json.contains("\"tags\":\"[\\\"one\\\",\\\"two\\\"]\""));
        assertTrue(json.contains("\"trace.id\":\"0123456789abcdef0123456789abcdef\""));
        assertTrue(json.contains("\"span.id\":\"0123456789abcdef\""));
        assertTrue(json.contains("\"logyard.trace_flags\":\"01\""));
        assertFalse(json.contains("\"trace_id\":"));
        assertFalse(json.contains("\"span_id\":"));
    }

    @Test
    void appliesCustomExclusionsAndRenamesBeforeProjection() {
        JsonProfile profile = JsonProfile.custom("private", "ecs", Map.of("resource", "application"),
                List.of("exception", "thread"), new JsonAttributeTransform(JsonAttributeTransform.Mode.NESTED,
                        null, List.of(), List.of("trace_id", "secret"), Map.of("span_id", "custom_span")));
        String json = encode(profile, AttributeSet.builder().put("trace_id", "secret-trace")
                .put("span_id", "span-value").put("secret", "private-value").build());

        assertTrue(json.contains("\"application\":{\"name\":\"orders\""));
        assertTrue(json.contains("\"custom_span\":\"span-value\""));
        assertFalse(json.contains("secret-trace"));
        assertFalse(json.contains("private-value"));
        assertFalse(json.contains("\"span.id\":"));
        assertFalse(json.contains("\"error\":"));
        assertFalse(json.contains("\"process.thread\":"));
    }

    @Test
    void droppingAttributesOrResourceDropsEveryProjectedField() {
        JsonProfile profile = JsonProfile.custom("private", "ecs", Map.of(),
                List.of("attributes", "resource"), JsonAttributeTransform.nested());
        String json = encode(profile, AttributeSet.of("trace_id", "secret-trace"));

        assertFalse(json.contains("secret-trace"));
        assertFalse(json.contains("\"trace.id\":"));
        assertFalse(json.contains("\"labels\":"));
        assertFalse(json.contains("\"service\":"));
        assertFalse(json.contains("\"logyard.resource\":"));
        assertFalse(json.contains("west"));
    }

    @Test
    void explicitFlatteningKeepsTheChosenNamesAndValueTypes() {
        JsonProfile profile = JsonProfile.custom("flat", "ecs", Map.of(), List.of(),
                new JsonAttributeTransform(JsonAttributeTransform.Mode.FLATTEN, "field.",
                        List.of(), List.of(), Map.of()));
        String json = encode(profile, AttributeSet.builder().put("count", 7).put("trace_id", "trace").build());

        assertTrue(json.contains("\"field.count\":7"));
        assertTrue(json.contains("\"field.trace_id\":\"trace\""));
        assertFalse(json.contains("\"labels\":"));
        assertFalse(json.contains("\"trace.id\":"));
    }

    @Test
    void reservesProjectedFieldNamesAgainstCustomCollisions() {
        for (String reserved : EcsProjection.RESERVED) {
            assertThrows(IllegalArgumentException.class, () -> JsonProfile.custom("collision", "ecs",
                    Map.of("body", reserved), List.of(), JsonAttributeTransform.nested()));
        }
        assertThrows(IllegalArgumentException.class, () -> JsonProfile.custom("collision", "ecs", Map.of(), List.of(),
                new JsonAttributeTransform(JsonAttributeTransform.Mode.FLATTEN, "trace.", List.of(), List.of(), Map.of())));
    }

    private static String encode(JsonProfile profile, AttributeSet attributes) {
        IllegalStateException failure = new IllegalStateException("failed", new RuntimeException("cause"));
        failure.addSuppressed(new IllegalArgumentException("suppressed"));
        failure.setStackTrace(new StackTraceElement[] {new StackTraceElement("orders.Service", "run", "Service.java", 7)});
        String json = new JsonEncoder(RESOURCE, profile).encode(new LogEvent(0, 12_000_000, Level.INFO,
                "orders.Service", "order.accepted", "accepted {}", new Object[] {7}, attributes, failure, 7, "worker"));
        JsonSyntaxValidator.requireValid(json);
        return json;
    }
}
