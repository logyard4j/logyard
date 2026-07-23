package com.zsumz.logyard.api.diagnostics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, bounded health snapshot for one named runtime component. */
public record ComponentHealth(
        String name,
        String kind,
        HealthStatus status,
        Map<String, String> details,
        Map<String, Long> metrics) {
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_NAME_CHARACTERS = 256;
    public static final int MAX_DETAIL_CHARACTERS = 2_048;

    public ComponentHealth {
        name = requireText(name, "name", MAX_NAME_CHARACTERS);
        kind = requireText(kind, "kind", MAX_NAME_CHARACTERS);
        Objects.requireNonNull(status, "status");
        details = boundedDetails(details);
        metrics = boundedMetrics(metrics);
    }

    public static ComponentHealth healthy(String name, String kind) {
        return new ComponentHealth(name, kind, HealthStatus.HEALTHY, Map.of(), Map.of());
    }

    private static Map<String, String> boundedDetails(Map<String, String> source) {
        Objects.requireNonNull(source, "details");
        requireEntryCount(source, "details");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(
                requireText(key, "details key", MAX_NAME_CHARACTERS),
                requireText(value, "details value", MAX_DETAIL_CHARACTERS)));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Long> boundedMetrics(Map<String, Long> source) {
        Objects.requireNonNull(source, "metrics");
        requireEntryCount(source, "metrics");
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(
                requireText(key, "metrics key", MAX_NAME_CHARACTERS),
                Objects.requireNonNull(value, "metrics value")));
        return Collections.unmodifiableMap(result);
    }

    private static void requireEntryCount(Map<?, ?> source, String name) {
        if (source.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_ENTRIES + " entries");
        }
    }

    private static String requireText(String value, String name, int maximumCharacters) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized.length() <= maximumCharacters
                ? normalized
                : normalized.substring(0, maximumCharacters - 3) + "...";
    }
}
