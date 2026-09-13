package com.logyard4j.logyard.output.console.rendering;

import com.logyard4j.logyard.api.event.CaptureLimits;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/** Renders the closed captured-value model with independent depth, entry, identity, and text bounds. */
final class ConsoleValueRenderer {
    private final ConsoleTextBuffer output;
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    private int remainingEntries = CaptureLimits.MAX_EVENT_ENTRIES;

    ConsoleValueRenderer(int maximumCharacters) {
        output = new ConsoleTextBuffer(maximumCharacters);
    }

    String render(Object value) {
        append(value, 0);
        return output.finish();
    }

    private void append(Object value, int depth) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String string) {
            output.appendSanitized(string);
        } else if (value instanceof Character character) {
            output.appendSanitized(character.toString());
        } else if (value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value.getClass() == BigInteger.class
                || value.getClass() == BigDecimal.class) {
            output.append(String.valueOf(value));
        } else if (depth >= CaptureLimits.MAX_NESTING_DEPTH) {
            output.append("[maximum rendering depth reached]");
        } else if (seen.put(value, Boolean.TRUE) != null) {
            output.append("[shared reference]");
        } else if (value instanceof Map<?, ?> map) {
            appendMap(map, depth);
        } else if (value instanceof Collection<?> collection) {
            appendCollection(collection, depth);
        } else if (value.getClass().isArray()) {
            appendArray(value, depth);
        } else {
            output.append("[unsupported captured value: ");
            output.append(value.getClass().getName());
            output.append(']');
        }
    }

    private void appendArray(Object array, int depth) {
        output.append('[');
        int length = Array.getLength(array);
        int index = 0;
        while (index < length) {
            if (index >= CaptureLimits.MAX_COLLECTION_ELEMENTS || !claimEntry() || output.full()) {
                appendSeparator(index);
                output.append("[output traversal budget exhausted]");
                break;
            }
            appendSeparator(index);
            append(Array.get(array, index), depth + 1);
            index++;
        }
        output.append(']');
    }

    private void appendMap(Map<?, ?> map, int depth) {
        output.append('{');
        int index = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (index >= CaptureLimits.MAX_COLLECTION_ELEMENTS || !claimEntry() || output.full()) {
                appendSeparator(index);
                output.append("[output traversal budget exhausted]");
                break;
            }
            appendSeparator(index++);
            append(entry.getKey(), depth + 1);
            output.append('=');
            append(entry.getValue(), depth + 1);
        }
        output.append('}');
    }

    private void appendCollection(Collection<?> collection, int depth) {
        output.append('[');
        int index = 0;
        for (Object item : collection) {
            if (index >= CaptureLimits.MAX_COLLECTION_ELEMENTS || !claimEntry() || output.full()) {
                appendSeparator(index);
                output.append("[output traversal budget exhausted]");
                break;
            }
            appendSeparator(index++);
            append(item, depth + 1);
        }
        output.append(']');
    }

    private boolean claimEntry() {
        if (remainingEntries == 0) {
            return false;
        }
        remainingEntries--;
        return true;
    }

    private void appendSeparator(int index) {
        if (index > 0) {
            output.append(", ");
        }
    }
}
