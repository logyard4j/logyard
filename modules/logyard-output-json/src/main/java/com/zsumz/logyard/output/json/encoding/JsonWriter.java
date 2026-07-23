package com.zsumz.logyard.output.json.encoding;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/** Direct JSON token writer with bounded recursive value traversal and no intermediate event tree. */
final class JsonWriter {
    private static final int MAX_NESTING = 12;

    private final StringBuilder buffer;

    JsonWriter(int initialCapacity) {
        buffer = new StringBuilder(initialCapacity);
    }

    void reset() {
        buffer.setLength(0);
    }

    String result() {
        return buffer.toString();
    }

    void beginObject() {
        buffer.append('{');
    }

    void endObject() {
        buffer.append('}');
    }

    void beginArray() {
        buffer.append('[');
    }

    void endArray() {
        buffer.append(']');
    }

    void comma() {
        buffer.append(',');
    }

    void name(String value) {
        string(value);
        buffer.append(':');
    }

    void field(String name, String value) {
        name(name);
        string(value);
    }

    void field(String name, long value) {
        name(name);
        buffer.append(value);
    }

    void field(String name, boolean value) {
        name(name);
        buffer.append(value);
    }

    void number(long value) {
        buffer.append(value);
    }

    void string(String value) {
        buffer.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> buffer.append("\\\"");
                case '\\' -> buffer.append("\\\\");
                case '\b' -> buffer.append("\\b");
                case '\f' -> buffer.append("\\f");
                case '\n' -> buffer.append("\\n");
                case '\r' -> buffer.append("\\r");
                case '\t' -> buffer.append("\\t");
                default -> {
                    if (character < 0x20 || Character.isSurrogate(character)) {
                        appendUnicode(character);
                    } else {
                        buffer.append(character);
                    }
                }
            }
        }
        buffer.append('"');
    }

    void value(Object value) {
        value(value, new IdentityHashMap<>(), 0);
    }

    private void value(Object value, IdentityHashMap<Object, Boolean> visiting, int depth) {
        if (value == null) {
            buffer.append("null");
        } else if (value instanceof String || value instanceof Character || value instanceof Enum<?>) {
            string(String.valueOf(value));
        } else if (value instanceof Boolean booleanValue) {
            buffer.append(booleanValue);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger) {
            buffer.append(value);
        } else if (value instanceof Float floatValue) {
            finiteNumber(floatValue.doubleValue(), value.toString());
        } else if (value instanceof Double doubleValue) {
            finiteNumber(doubleValue, value.toString());
        } else if (value instanceof java.math.BigDecimal decimal) {
            buffer.append(decimal.toPlainString());
        } else if (depth >= MAX_NESTING || visiting.put(value, Boolean.TRUE) != null) {
            string("[truncated]");
        } else {
            try {
                compoundValue(value, visiting, depth);
            } finally {
                visiting.remove(value);
            }
        }
    }

    private void compoundValue(Object value, IdentityHashMap<Object, Boolean> visiting, int depth) {
        if (value instanceof Map<?, ?> map) {
            beginObject();
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (index++ > 0) {
                    comma();
                }
                name(String.valueOf(entry.getKey()));
                value(entry.getValue(), visiting, depth + 1);
            }
            endObject();
        } else if (value instanceof Collection<?> collection) {
            beginArray();
            int index = 0;
            for (Object item : collection) {
                if (index++ > 0) {
                    comma();
                }
                value(item, visiting, depth + 1);
            }
            endArray();
        } else if (value.getClass().isArray()) {
            beginArray();
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                if (index > 0) {
                    comma();
                }
                value(Array.get(value, index), visiting, depth + 1);
            }
            endArray();
        } else {
            string(String.valueOf(value));
        }
    }

    private void appendUnicode(char character) {
        String hex = Integer.toHexString(character);
        buffer.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
    }

    private void finiteNumber(double value, String representation) {
        if (Double.isFinite(value)) {
            buffer.append(representation);
        } else {
            string(representation);
        }
    }
}
