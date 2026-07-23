package com.zsumz.logyard.output.console.rendering;

import java.util.Collection;
import java.util.Map;

final class ConsoleText {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ConsoleText() {
    }

    static String safe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence sequence) {
            return sanitize(sequence.toString());
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder result = new StringBuilder("{");
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (index++ > 0) {
                    result.append(", ");
                }
                result.append(safe(entry.getKey())).append('=').append(safe(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder result = new StringBuilder("[");
            int index = 0;
            for (Object item : collection) {
                if (index++ > 0) {
                    result.append(", ");
                }
                result.append(safe(item));
            }
            return result.append(']').toString();
        }
        return sanitize(String.valueOf(value));
    }

    static String sanitize(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                default -> {
                    if (isUnsafeControl(character)) {
                        result.append("\\u")
                                .append(HEX[(character >>> 12) & 0xf])
                                .append(HEX[(character >>> 8) & 0xf])
                                .append(HEX[(character >>> 4) & 0xf])
                                .append(HEX[character & 0xf]);
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.toString();
    }
    private static boolean isUnsafeControl(char character) {
        return Character.isISOControl(character)
                || character == '\u061c'
                || character == '\u200e'
                || character == '\u200f'
                || (character >= '\u202a' && character <= '\u202e')
                || (character >= '\u2066' && character <= '\u2069');
    }
}
