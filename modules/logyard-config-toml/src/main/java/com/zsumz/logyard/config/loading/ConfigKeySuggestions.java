package com.zsumz.logyard.config.loading;

import java.util.Set;

/** Finds conservative typo suggestions within Logyard's strict configuration vocabulary. */
final class ConfigKeySuggestions {
    private static final Set<String> KNOWN_KEYS = Set.of(
            "schema", "service", "resource", "runtime", "context", "loggers",
            "delivery", "outputs", "themes", "formatters", "encoders",
            "json_profiles", "enrichers", "filters", "name", "namespace", "version",
            "environment", "instance_id", "attributes", "shutdown_timeout",
            "internal_status", "watch", "reload_debounce", "trace", "mdc",
            "baggage", "redact", "mode", "capacity", "overflow", "action",
            "timeout", "type", "stream", "min_level", "formatter", "encoder",
            "provider", "implementation", "config", "template", "profile", "preset",
            "rename", "drop", "prefix", "include", "exclude", "probability", "key",
            "seed", "permits_per_second", "burst", "max_keys", "color", "exception",
            "capability", "theme", "style", "common_frames", "path", "buffer",
            "flush", "append", "rotate", "size", "keep", "compression",
            "endpoint", "headers", "batch", "max_events", "max_bytes", "retry",
            "max_attempts", "max_elapsed", "initial_backoff", "max_backoff",
            "circuit_breaker", "failure_threshold", "open_duration", "level",
            "enrich", "fg", "bg", "bold", "dim", "italic", "underline");

    private ConfigKeySuggestions() {
    }

    static String nearest(String value) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : KNOWN_KEYS) {
            int distance = levenshtein(value, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return bestDistance <= Math.max(2, value.length() / 3) ? best : null;
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) {
            previous[index] = index;
        }
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            current[0] = leftIndex;
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                int cost = left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1) ? 0 : 1;
                current[rightIndex] = Math.min(
                        Math.min(current[rightIndex - 1] + 1, previous[rightIndex] + 1),
                        previous[rightIndex - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
