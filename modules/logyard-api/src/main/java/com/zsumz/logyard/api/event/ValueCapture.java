package com.zsumz.logyard.api.event;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.UUID;

/** Captures caller-owned values before asynchronous delivery can observe later mutation. */
final class ValueCapture {
    private static final Object[] EMPTY_ARGUMENTS = new Object[0];

    private ValueCapture() {
    }

    static Object[] arguments(Object[] values) {
        if (values == null || values.length == 0) {
            return EMPTY_ARGUMENTS;
        }
        int length = Math.min(values.length, CaptureLimits.MAX_ARGUMENTS);
        Object[] captured = new Object[length];
        for (int index = 0; index < length; index++) {
            captured[index] = capture(values[index], null, 0);
        }
        return captured;
    }

    static Object capture(Object value) {
        return capture(value, null, 0);
    }

    private static Object capture(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return CaptureLimits.text(string);
        }
        if (value instanceof CharSequence sequence) {
            return MessageFormatter.safeToString(sequence);
        }
        if (isImmutableScalar(value)) {
            return value;
        }
        if (value instanceof TemporalAccessor || value instanceof TemporalAmount
                || value instanceof UUID || value instanceof Class<?>) {
            return CaptureLimits.text(MessageFormatter.safeToString(value));
        }
        if (!requiresGraphTracking(value)) {
            return CaptureLimits.text(MessageFormatter.safeToString(value));
        }
        if (depth >= CaptureLimits.MAX_NESTING_DEPTH) {
            return "[maximum nesting depth reached]";
        }
        IdentityHashMap<Object, Boolean> graph = visiting == null ? new IdentityHashMap<>() : visiting;
        if (graph.put(value, Boolean.TRUE) != null) {
            return "[circular reference]";
        }
        try {
            if (value.getClass().isArray() && value.getClass().getComponentType().isPrimitive()) {
                int sourceLength = Array.getLength(value);
                int length = Math.min(sourceLength, CaptureLimits.MAX_COLLECTION_ELEMENTS);
                List<Object> result = new ArrayList<>(length + (sourceLength > length ? 1 : 0));
                for (int index = 0; index < length; index++) {
                    result.add(Array.get(value, index));
                }
                appendOmission(result, sourceLength - length, "element");
                return Collections.unmodifiableList(result);
            }
            if (value instanceof Object[] array) {
                int length = Math.min(array.length, CaptureLimits.MAX_COLLECTION_ELEMENTS);
                List<Object> result = new ArrayList<>(length + (array.length > length ? 1 : 0));
                for (int index = 0; index < length; index++) {
                    result.add(capture(array[index], graph, depth + 1));
                }
                appendOmission(result, array.length - length, "element");
                return Collections.unmodifiableList(result);
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                int index = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (index >= CaptureLimits.MAX_COLLECTION_ELEMENTS) {
                        break;
                    }
                    result.put(
                            CaptureLimits.attributeKey(MessageFormatter.safeToString(entry.getKey())),
                            capture(entry.getValue(), graph, depth + 1));
                    index++;
                }
                int omitted = Math.max(0, map.size() - index);
                if (omitted > 0) {
                    result.put(uniqueTruncationKey(result), "[" + omitted + " map entr"
                            + (omitted == 1 ? "y" : "ies") + " omitted]");
                }
                return Collections.unmodifiableMap(result);
            }
            if (value instanceof Collection<?> collection) {
                int expected = Math.min(collection.size(), CaptureLimits.MAX_COLLECTION_ELEMENTS);
                List<Object> result = new ArrayList<>(expected + 1);
                int index = 0;
                for (Object item : collection) {
                    if (index >= CaptureLimits.MAX_COLLECTION_ELEMENTS) {
                        break;
                    }
                    result.add(capture(item, graph, depth + 1));
                    index++;
                }
                appendOmission(result, Math.max(0, collection.size() - index), "item");
                return Collections.unmodifiableList(result);
            }
        } finally {
            graph.remove(value);
        }
        throw new AssertionError("unreachable graph value");
    }

    private static boolean requiresGraphTracking(Object value) {
        return value.getClass().isArray() || value instanceof Map<?, ?> || value instanceof Collection<?>;
    }

    private static void appendOmission(List<Object> values, int omitted, String noun) {
        if (omitted > 0) {
            values.add("[" + omitted + ' ' + noun + (omitted == 1 ? "" : "s") + " omitted]");
        }
    }

    private static String uniqueTruncationKey(Map<String, Object> values) {
        String key = "logyard.truncated";
        int suffix = 1;
        while (values.containsKey(key)) {
            key = "logyard.truncated." + suffix++;
        }
        return key;
    }

    private static boolean isImmutableScalar(Object value) {
        return value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof BigDecimal
                || value instanceof Character
                || value instanceof Enum<?>;
    }

    /** Retained only for binary compatibility with early local builds. */
    @SuppressWarnings("unused")
    private static final class ImmutableArray extends AbstractList<Object> implements RandomAccess {
        private final Object values;

        private ImmutableArray(Object values) {
            this.values = values;
        }

        @Override
        public Object get(int index) {
            return Array.get(values, index);
        }

        @Override
        public int size() {
            return Array.getLength(values);
        }
    }
}
