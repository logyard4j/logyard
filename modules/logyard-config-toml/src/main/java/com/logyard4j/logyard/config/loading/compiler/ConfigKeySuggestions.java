package com.logyard4j.logyard.config.loading.compiler;

import com.logyard4j.logyard.config.schema.ConfigSchema;

import java.util.Set;

/** Finds conservative typo suggestions within Logyard's strict configuration vocabulary. */
final class ConfigKeySuggestions {
    private ConfigKeySuggestions() {
    }

    /**
     * Returns the nearest known key for the unknown key at one table path, or null.
     *
     * <p>When the table's schema keys are known, only those keys are candidates so a
     * suggestion never points at a key another section owns; free-form tables fall back
     * to the complete vocabulary.</p>
     */
    static String nearest(String value, String tablePath) {
        Set<String> scoped = ConfigSchema.keysFor(tablePath);
        return nearestOf(value, scoped.isEmpty() ? ConfigSchema.allKeys() : scoped);
    }

    private static String nearestOf(String value, Set<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
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
