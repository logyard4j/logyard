package com.logyard4j.logyard.api.diagnostics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, bounded health snapshot for one named runtime component.
 *
 * <p>Text is trimmed and shortened without splitting Unicode characters. Detail and metric
 * keys must remain distinct after normalization; collisions are rejected instead of losing data.</p>
 *
 * @param name stable component name
 * @param kind component category
 * @param status current operational state
 * @param details bounded human-readable details
 * @param metrics bounded numeric measurements
 */
public record ComponentHealth(
        String name,
        String kind,
        HealthStatus status,
        Map<String, String> details,
        Map<String, Long> metrics) {
    /** Maximum number of detail or metric entries. */
    public static final int MAX_ENTRIES = 64;

    /** Maximum length of component names, kinds, and map keys. */
    public static final int MAX_NAME_CHARACTERS = 256;

    /** Maximum length of each detail value. */
    public static final int MAX_DETAIL_CHARACTERS = 2_048;

    /**
     * Normalizes and bounds the supplied snapshot values.
     *
     * @throws IllegalArgumentException if text is blank, an entry limit is exceeded, or keys collide after normalization
     */
    public ComponentHealth {
        name = requireText(name, "name", MAX_NAME_CHARACTERS);
        kind = requireText(kind, "kind", MAX_NAME_CHARACTERS);
        Objects.requireNonNull(status, "status");
        details = boundedDetails(details);
        metrics = boundedMetrics(metrics);
    }

    /**
     * Creates a healthy component with no details or metrics.
     *
     * @param name stable component name
     * @param kind component category
     * @return healthy component snapshot
     */
    public static ComponentHealth healthy(String name, String kind) {
        return new ComponentHealth(name, kind, HealthStatus.HEALTHY, Map.of(), Map.of());
    }

    private static Map<String, String> boundedDetails(Map<String, String> source) {
        Objects.requireNonNull(source, "details");
        requireEntryCount(source, "details");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        source.forEach((key, value) -> putUnique(result,
                requireText(key, "details key", MAX_NAME_CHARACTERS),
                requireText(value, "details value", MAX_DETAIL_CHARACTERS), "details"));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Long> boundedMetrics(Map<String, Long> source) {
        Objects.requireNonNull(source, "metrics");
        requireEntryCount(source, "metrics");
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        source.forEach((key, value) -> putUnique(result,
                requireText(key, "metrics key", MAX_NAME_CHARACTERS),
                Objects.requireNonNull(value, "metrics value"), "metrics"));
        return Collections.unmodifiableMap(result);
    }

    private static <V> void putUnique(Map<String, V> target, String key, V value, String name) {
        if (target.putIfAbsent(key, value) != null) {
            throw new IllegalArgumentException("duplicate normalized " + name + " key: " + key);
        }
    }

    private static void requireEntryCount(Map<?, ?> source, String name) {
        if (source.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_ENTRIES + " entries");
        }
    }

    private static String requireText(String value, String name, int maximumCharacters) {
        Objects.requireNonNull(value, name);
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) <= ' ') start++;
        while (start < end && value.charAt(end - 1) <= ' ') end--;
        if (start == end) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (end - start <= maximumCharacters) {
            return value.substring(start, end);
        }
        // Bound the copy itself; trimming a large source first would allocate its entire interior.
        int limit = start + maximumCharacters - 3;
        if (Character.isHighSurrogate(value.charAt(limit - 1)) && Character.isLowSurrogate(value.charAt(limit))) {
            limit--;
        }
        return value.substring(start, limit) + "...";
    }
}
