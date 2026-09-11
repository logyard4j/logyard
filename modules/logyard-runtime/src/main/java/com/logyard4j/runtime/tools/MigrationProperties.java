package com.logyard4j.runtime.tools;

import java.util.Map;

/** Expands local XML properties with finite work and output budgets. */
final class MigrationProperties {
    static final int MAX_CHARACTERS = 1_048_576;

    private MigrationProperties() {
    }

    static String expand(String value, Map<String, String> properties, int remaining) {
        if (value == null) return null;
        if (value.length() > remaining) throw tooLarge();
        if (!value.contains("${")) return value;
        String current = value;
        for (int pass = 0; pass < 8; pass++) {
            StringBuilder result = new StringBuilder(Math.min(current.length(), 4_096));
            int cursor = 0;
            boolean replaced = false;
            while (cursor < current.length()) {
                int opening = current.indexOf("${", cursor);
                int closing = opening < 0 ? -1 : current.indexOf('}', opening + 2);
                if (closing < 0) {
                    append(result, current.substring(cursor), remaining);
                    break;
                }
                append(result, current.substring(cursor, opening), remaining);
                String expression = current.substring(opening + 2, closing);
                int fallback = expression.indexOf(":-");
                String name = fallback < 0 ? expression : expression.substring(0, fallback);
                String replacement = properties.get(name);
                if (replacement == null) {
                    append(result, current.substring(opening, closing + 1), remaining);
                } else {
                    append(result, replacement, remaining);
                    replaced = true;
                }
                cursor = closing + 1;
            }
            String next = result.toString();
            if (!replaced) return next;
            if (next.equals(current)) throw new IllegalArgumentException("cyclic migration property reference");
            if (!next.contains("${")) return next;
            current = next;
        }
        throw new IllegalArgumentException("migration property expansion exceeds 8 passes or contains a cycle");
    }

    private static void append(StringBuilder target, String value, int limit) {
        if (value.length() > limit - target.length()) throw tooLarge();
        target.append(value);
    }

    private static IllegalArgumentException tooLarge() {
        return new IllegalArgumentException("migration property expansion exceeds its 1MiB total budget");
    }
}
