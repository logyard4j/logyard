package com.logyard4j.logyard.config.encoding;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonTextBoundaryTest {
    @Test
    void oversizedProfileTextProducesBoundedDiagnostics() {
        String oversized = " " + "x".repeat(1_000_000) + " ";

        assertBoundedFailure(() -> new JsonAttributeTransformConfig(
                "nested", "attributes.", List.of(oversized), List.of(), Map.of()));
        assertBoundedFailure(() -> new JsonProfileConfig(
                "custom", "logyard", Map.of("logger", oversized), List.of(),
                JsonAttributeTransformConfig.nested()));
        assertBoundedFailure(() -> new JsonProfileConfig(
                "custom", oversized, Map.of(), List.of(), JsonAttributeTransformConfig.nested()));
    }

    @Test
    void attributeSelectorsAreNormalizedBeforeStorageAndUniquenessChecks() {
        String name = "x".repeat(256);
        String padding = " \t\r\n".repeat(1_000);
        JsonAttributeTransformConfig config = new JsonAttributeTransformConfig(
                " flatten ", "attributes.", List.of(padding + name + padding), List.of(), Map.of());

        assertEquals("flatten", config.mode());
        assertEquals(List.of(name), config.include());
        assertThrows(IllegalArgumentException.class, () -> new JsonAttributeTransformConfig(
                "nested", "attributes.", List.of(name, padding + name + padding), List.of(), Map.of()));
    }

    private static void assertBoundedFailure(Executable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action);
        assertTrue(failure.getMessage().length() <= 128, "oversized JSON diagnostics must remain bounded");
    }
}
