package com.zsumz.logyard.output.json.encoding;

import com.zsumz.logyard.api.event.SystemAttributes;

import com.zsumz.logyard.api.event.CaptureLimits;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Renders a bounded captured-value graph through one JSON token writer. */
final class JsonValueWriter {
    private final JsonWriter json;
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    private int remainingEntries;
    private boolean traversalTruncated;

    JsonValueWriter(JsonWriter json) {
        this.json = Objects.requireNonNull(json, "json");
        reset();
    }

    void reset() {
        seen.clear();
        remainingEntries = CaptureLimits.MAX_EVENT_ENTRIES;
        traversalTruncated = false;
    }

    boolean traversalTruncated() {
        return traversalTruncated;
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

    void write(Object value) {
        write(value, 0);
    }

    private void write(Object value, int depth) {
        if (value == null) {
            json.literal("null");
        } else if (value instanceof String string) {
            json.string(string);
        } else if (value instanceof Character character) {
            json.string(character.toString());
        } else if (value instanceof Boolean booleanValue) {
            json.literal(booleanValue);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            json.literal(String.valueOf(value));
        } else if (value instanceof Float floatValue) {
            finiteNumber(floatValue.doubleValue(), floatValue.toString());
        } else if (value instanceof Double doubleValue) {
            finiteNumber(doubleValue, doubleValue.toString());
        } else if (value.getClass() == BigInteger.class || value.getClass() == BigDecimal.class) {
            json.literal(String.valueOf(value));
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
        json.beginObject();
        int index = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (index >= CaptureLimits.MAX_COLLECTION_ELEMENTS || !claimEntry()) {
                appendMapTruncation(index);
                break;
            }
            if (index++ > 0) {
                json.comma();
            }
            json.name(entry.getKey() instanceof String key ? key : "[non-string captured key]");
            write(entry.getValue(), depth + 1);
        }
        json.endObject();
    }

    private void collection(Collection<?> collection, int depth) {
        json.beginArray();
        int index = 0;
        for (Object item : collection) {
            if (index >= CaptureLimits.MAX_COLLECTION_ELEMENTS || !claimEntry()) {
                appendArrayTruncation(index);
                break;
            }
            if (index++ > 0) {
                json.comma();
            }
            write(item, depth + 1);
        }
        json.endArray();
    }

    private void array(Object array, int depth) {
        json.beginArray();
        int sourceLength = Array.getLength(array);
        int index = 0;
        while (index < sourceLength) {
            if (index >= CaptureLimits.MAX_COLLECTION_ELEMENTS || !claimEntry()) {
                appendArrayTruncation(index);
                break;
            }
            if (index > 0) {
                json.comma();
            }
            write(Array.get(array, index), depth + 1);
            index++;
        }
        json.endArray();
    }

    private void appendMapTruncation(int priorEntries) {
        traversalTruncated = true;
        if (priorEntries > 0) {
            json.comma();
        }
        json.field(SystemAttributes.OUTPUT_TRUNCATED, true);
    }

    private void appendArrayTruncation(int priorEntries) {
        traversalTruncated = true;
        if (priorEntries > 0) {
            json.comma();
        }
        json.string("[output traversal budget exhausted]");
    }

    private void truncatedValue(String marker) {
        traversalTruncated = true;
        json.string(marker);
    }

    private void finiteNumber(double value, String representation) {
        if (Double.isFinite(value)) {
            json.literal(representation);
        } else {
            json.string(representation);
        }
    }
}
