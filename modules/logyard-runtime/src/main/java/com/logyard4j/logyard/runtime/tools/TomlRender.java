package com.logyard4j.logyard.runtime.tools;

import java.util.List;
import java.util.Map;

/** Renders parsed configuration values back into TOML syntax. */
final class TomlRender {
    private TomlRender() {
    }

    static String value(Object value) {
        if (value instanceof String text) {
            return string(text);
        }
        if (value instanceof Map<?, ?> table) {
            StringBuilder result = new StringBuilder("{ ");
            boolean first = true;
            for (Map.Entry<?, ?> entry : table.entrySet()) {
                if (!first) {
                    result.append(", ");
                }
                first = false;
                result.append(key(String.valueOf(entry.getKey()))).append(" = ").append(value(entry.getValue()));
            }
            return result.append(first ? "}" : " }").toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    result.append(", ");
                }
                result.append(value(list.get(index)));
            }
            return result.append("]").toString();
        }
        return String.valueOf(value);
    }

    static String key(String key) {
        return key.matches("[A-Za-z0-9_-]+") ? key : string(key);
    }

    static String string(String text) {
        StringBuilder result = new StringBuilder(text.length() + 2).append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (Character.isISOControl(character)) {
                        result.append(String.format("\\u%04X", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }
}
