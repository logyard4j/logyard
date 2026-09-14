package com.logyard4j.logyard.output.json.encoding;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonTextBoundaryTest {
    @Test
    void oversizedProfileTextProducesBoundedDiagnostics() {
        String oversized = " " + "x".repeat(1_000_000) + " ";

        assertBoundedFailure(() -> JsonProfile.named(oversized));
        assertBoundedFailure(() -> new JsonAttributeTransform(
                JsonAttributeTransform.Mode.NESTED,
                "attributes.",
                List.of(oversized),
                List.of(),
                Map.of()));
        assertBoundedFailure(() -> JsonProfile.custom(
                "custom", "logyard", Map.of("logger", oversized), List.of(),
                JsonAttributeTransform.nested()));
        assertBoundedFailure(() -> JsonProfile.custom(
                "custom", oversized, Map.of(), List.of(), JsonAttributeTransform.nested()));
    }

    @Test
    void exactAttributeLimitRemainsSupportedWithLargeWhitespacePadding() {
        String name = "x".repeat(256);
        String padding = " \t\r\n".repeat(1_000);
        JsonAttributeTransform transform = new JsonAttributeTransform(
                JsonAttributeTransform.Mode.NESTED,
                "attributes.",
                List.of(padding + name + padding),
                List.of(),
                Map.of());

        assertTrue(transform.includes(name));
    }

    private static void assertBoundedFailure(Executable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action);
        assertTrue(failure.getMessage().length() <= 128, "oversized JSON diagnostics must remain bounded");
    }
}
