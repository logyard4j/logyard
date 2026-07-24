package com.zsumz.logyard.output.json.encoding;

import com.zsumz.logyard.api.event.CaptureLimits;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/** Direct JSON token writer with hard character, traversal, identity, and depth bounds. */
final class JsonWriter {
    private final JsonBuffer buffer;
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    private int remainingEntries;
    private boolean traversalTruncated;

    JsonWriter(int initialCapacity) {
        buffer = new JsonBuffer(initialCapacity, JsonOutputLimits.MAX_RECORD_CHARACTERS);
        reset();
    }

    void reset() {
        buffer.reset();
        seen.clear();
        remainingEntries = CaptureLimits.MAX_EVENT_ENTRIES;
        traversalTruncated = false;
    }

    String result() {
        return buffer.result();
    }

    boolean traversalTruncated() {
        return traversalTruncated;
    }

    int retainedCapacity() {
        return buffer.capacity();
    }

    boolean claimEntry() {
        if (remainingEntries == 0) {
            traversalTruncated = true;
            return false;
        }
        remainingEntries--;
        return true;
    }

    void markTraversalTruncated() {
        traversalTruncated = true;
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
        value(value, 0);
    }

    private void value(Object value, int depth) {
        if (value == null) {
            buffer.append("null");
        } else if (value instanceof String string) {
            string(string);
        } else if (value instanceof Character character) {
            string(character.toString());
        } else if (value instanceof Boolean booleanValue) {
            buffer.append(booleanValue);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            buffer.append(String.valueOf(value));
        } else if (value instanceof Float floatValue) {
            finiteNumber(floatValue.doubleValue(), floatValue.toString());
        } else if (value instanceof Double doubleValue) {
            finiteNumber(doubleValue, doubleValue.toString());
        } else if (value.getClass() == BigInteger.class || value.getClass() == BigDecimal.class) {
            buffer.append(String.valueOf(value));
        } else if (depth >= CaptureLimits.MAX_NESTING_DEPTH) {
            truncatedValue("[maximum rendering depth reached]");
        } else if (seen.put(value, Boolean.TRUE) != null) {
            truncatedValue("[shared reference]");
        } else {
            compoundValue(value, depth);
        }
    }

    private void compoundValue(Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            map(map, depth);
        } else if (value instanceof Collection<?> collection) {
            collection(collection, depth);
        } else if (value.getClass().isArray()) {
            array(value, depth);
        } else {
            truncatedValue("[unsupported captured value: " + value.getClass().getName() + ']');
        }
    }

    private void map(Map<?, ?> map, int depth) {
        beginObject();
        int index = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (index >= CaptureLimits.MAX_COLLECTION_ELEMENTS || !claimEntry()) {
                appendMapTruncation(index);
                break;
            }
            if (index++ > 0) {
                comma();
            }
            name(entry.getKey() instanceof String key ? key : "[non-string captured key]");
            value(entry.getValue(), depth + 1);
        }
        endObject();
    }

    private void collection(Collection<?> collection, int depth) {
        beginArray();
        int index = 0;
        for (Object item : collection) {
            if (index >= CaptureLimits.MAX_COLLECTION_ELEMENTS || !claimEntry()) {
                appendArrayTruncation(index);
                break;
            }
            if (index++ > 0) {
                comma();
            }
            value(item, depth + 1);
        }
        endArray();
    }

    private void array(Object array, int depth) {
        beginArray();
        int sourceLength = Array.getLength(array);
        int index = 0;
        while (index < sourceLength) {
            if (index >= CaptureLimits.MAX_COLLECTION_ELEMENTS || !claimEntry()) {
                appendArrayTruncation(index);
                break;
            }
            if (index > 0) {
                comma();
            }
            value(Array.get(array, index), depth + 1);
            index++;
        }
        endArray();
    }

    private void appendMapTruncation(int priorEntries) {
        traversalTruncated = true;
        if (priorEntries > 0) {
            comma();
        }
        field("logyard.output.truncated", true);
    }

    private void appendArrayTruncation(int priorEntries) {
        traversalTruncated = true;
        if (priorEntries > 0) {
            comma();
        }
        string("[output traversal budget exhausted]");
    }

    private void truncatedValue(String marker) {
        traversalTruncated = true;
        string(marker);
    }

    private void appendUnicode(char character) {
        String hex = Integer.toHexString(character);
        buffer.append("\\u");
        buffer.append("0".repeat(4 - hex.length()));
        buffer.append(hex);
    }

    private void finiteNumber(double value, String representation) {
        if (Double.isFinite(value)) {
            buffer.append(representation);
        } else {
            string(representation);
        }
    }
}
