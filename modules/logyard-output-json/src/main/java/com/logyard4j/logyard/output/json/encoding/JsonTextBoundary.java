package com.logyard4j.logyard.output.json.encoding;

import java.util.Objects;

/** Bounds JSON profile text before creating its normalized representation. */
final class JsonTextBoundary {
    private JsonTextBoundary() {
    }

    static String trim(String value, String label, int maximum) {
        Objects.requireNonNull(value, label);
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) <= ' ') start++;
        while (start < end && value.charAt(end - 1) <= ' ') end--;
        if (end - start > maximum) {
            throw new IllegalArgumentException(
                    label + " exceeds " + maximum + " characters after trimming");
        }
        return value.substring(start, end);
    }
}
