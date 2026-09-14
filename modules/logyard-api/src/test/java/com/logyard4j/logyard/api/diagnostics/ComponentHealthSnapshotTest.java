package com.logyard4j.logyard.api.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ComponentHealthSnapshotTest {
    @Test
    void normalizedKeysCannotSilentlyReplaceOtherDetailsOrMetrics() {
        rejectCollidingKeys("queued", " queued ");
        String prefix = "x".repeat(ComponentHealth.MAX_NAME_CHARACTERS);
        rejectCollidingKeys(prefix + "first", prefix + "second");
    }

    @Test
    void truncationPreservesCompleteUnicodeCharactersForEveryTextField() {
        String namePrefix = "x".repeat(ComponentHealth.MAX_NAME_CHARACTERS - 4);
        String detailPrefix = "y".repeat(ComponentHealth.MAX_DETAIL_CHARACTERS - 4);
        String key = namePrefix + "\ud83d\ude80tail";
        ComponentHealth health = new ComponentHealth(key, key, HealthStatus.HEALTHY,
                Map.of(key, detailPrefix + "\ud83d\ude80tail"), Map.of(key, 1L));

        String expectedKey = namePrefix + "...";
        assertEquals(expectedKey, health.name());
        assertEquals(expectedKey, health.kind());
        assertEquals(Map.of(expectedKey, detailPrefix + "..."), health.details());
        assertEquals(Map.of(expectedKey, 1L), health.metrics());
    }

    @Test
    void whitespaceNormalizationKeepsTheEstablishedTrimSemantics() {
        String padding = " \t\r\n".repeat(4_096);
        ComponentHealth health = new ComponentHealth(padding + "name" + padding, " output ", HealthStatus.HEALTHY,
                Map.of(" detail ", padding + "message" + padding), Map.of(" count ", 1L));
        assertEquals("name", health.name());
        assertEquals("output", health.kind());
        assertEquals(Map.of("detail", "message"), health.details());
        assertEquals(Map.of("count", 1L), health.metrics());
        assertThrows(IllegalArgumentException.class, () -> ComponentHealth.healthy(padding, "output"));
    }

    @Test
    void snapshotsPreserveMapOrderAndOwnTheirCollections() {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("second", "two");
        details.put("first", "one");
        Map<String, Long> metrics = new LinkedHashMap<>();
        metrics.put("second", 2L);
        metrics.put("first", 1L);
        ComponentHealth health = snapshot(details, metrics);
        details.clear();
        metrics.clear();
        assertEquals(List.of("second", "first"), List.copyOf(health.details().keySet()));
        assertEquals(List.of("second", "first"), List.copyOf(health.metrics().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> health.details().clear());
        assertThrows(UnsupportedOperationException.class, () -> health.metrics().clear());
    }

    private static void rejectCollidingKeys(String first, String second) {
        assertThrows(IllegalArgumentException.class, () -> snapshot(Map.of(first, "one", second, "two"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> snapshot(Map.of(), Map.of(first, 1L, second, 2L)));
    }

    private static ComponentHealth snapshot(Map<String, String> details, Map<String, Long> metrics) {
        return new ComponentHealth("name", "output", HealthStatus.HEALTHY, details, metrics);
    }
}
