package com.zsumz.logyard.api.event;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Captures caller-owned values before asynchronous delivery can observe later mutation. */
final class ValueCapture {
    private static final Object[] EMPTY_ARGUMENTS = new Object[0];
    private static final String DEPTH_MARKER = "[maximum nesting depth reached]";
    private static final String CYCLE_MARKER = "[circular reference]";
    private static final String BUDGET_MARKER = "[event capture budget exhausted]";

    private ValueCapture() {
    }

    static Object[] arguments(Object[] values, CaptureContext context) {
        if (values == null || values.length == 0) {
            return EMPTY_ARGUMENTS;
        }
        int length = Math.min(values.length, CaptureLimits.MAX_ARGUMENTS);
        Object[] captured = new Object[length];
        for (int index = 0; index < length; index++) {
            if (!context.claimEntry()) {
                return java.util.Arrays.copyOf(captured, index);
            }
            captured[index] = capture(values[index], context, 0);
        }
        if (values.length > length) {
            context.markTruncated();
        }
        return captured;
    }

    static Object capture(Object value) {
        return capture(value, CaptureContext.currentOrCreate(), 0);
    }

    static Object capture(Object value, CaptureContext context) {
        return capture(value, context, 0);
    }

    private static Object capture(Object value, CaptureContext context, int depth) {
        if (value == null || isImmutableScalar(value)) {
            return value;
        }
        Object completed = context.completedValue(value);
        if (completed != null) {
            return completed;
        }
        if (value instanceof String string) {
            return context.captureText(string, CaptureLimits.MAX_TEXT_CHARS);
        }
        if (depth >= CaptureLimits.MAX_NESTING_DEPTH) {
            context.markTruncated();
            return DEPTH_MARKER;
        }
        if (!context.claimNode()) {
            return BUDGET_MARKER;
        }
        if (context.remainingCharacters() == 0 && !requiresGraphTracking(value)) {
            context.markTruncated();
            return BUDGET_MARKER;
        }
        if (value instanceof CharSequence
                || value instanceof TemporalAccessor
                || value instanceof TemporalAmount
                || value instanceof UUID
                || value instanceof Class<?>) {
            String captured = captureRendered(value, context);
            context.completeValue(value, captured);
            return captured;
        }
        if (!requiresGraphTracking(value)) {
            String captured = captureRendered(value, context);
            context.completeValue(value, captured);
            return captured;
        }
        if (!context.visitValue(value)) {
            return CYCLE_MARKER;
        }
        try {
            Object captured = captureContainer(value, context, depth);
            context.completeValue(value, captured);
            return captured;
        } finally {
            context.leaveValue(value);
        }
    }

    private static Object captureContainer(Object value, CaptureContext context, int depth) {
        if (value.getClass().isArray()) {
            return captureArray(value, context, depth);
        }
        if (value instanceof Map<?, ?> map) {
            return captureMap(map, context, depth);
        }
        if (value instanceof Collection<?> collection) {
            return captureCollection(collection, context, depth);
        }
        throw new AssertionError("unreachable graph value");
    }

    private static List<Object> captureArray(Object array, CaptureContext context, int depth) {
        int sourceLength = Array.getLength(array);
        int length = Math.min(sourceLength, CaptureLimits.MAX_COLLECTION_ELEMENTS);
        List<Object> result = new ArrayList<>(Math.min(length + 1, CaptureLimits.MAX_COLLECTION_ELEMENTS + 1));
        int index = 0;
        for (; index < length; index++) {
            if (!context.claimEntry()) {
                break;
            }
            result.add(capture(Array.get(array, index), context, depth + 1));
        }
        if (sourceLength > index) {
            context.markTruncated();
            result.add(omission(sourceLength - index, "element"));
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, Object> captureMap(Map<?, ?> map, CaptureContext context, int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<? extends Map.Entry<?, ?>> entries = map.entrySet().iterator();
        int retained = 0;
        while (retained < CaptureLimits.MAX_COLLECTION_ELEMENTS && entries.hasNext()) {
            if (!context.claimEntry()) {
                result.put(uniqueTruncationKey(result), BUDGET_MARKER);
                return Collections.unmodifiableMap(result);
            }
            Map.Entry<?, ?> entry = entries.next();
            if (context.remainingCharacters() == 0) {
                context.markTruncated();
                result.put(uniqueTruncationKey(result), BUDGET_MARKER);
                return Collections.unmodifiableMap(result);
            }
            String key = context.captureText(
                    CaptureLimits.attributeKey(MessageFormatter.safeToString(
                            entry.getKey(),
                            Math.min(CaptureLimits.MAX_TEXT_CHARS, context.remainingCharacters()))),
                    CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
            result.put(key, capture(entry.getValue(), context, depth + 1));
            retained++;
        }
        if (entries.hasNext()) {
            entries.next();
            context.markTruncated();
            result.put(uniqueTruncationKey(result), "[additional map entries omitted]");
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<Object> captureCollection(Collection<?> collection, CaptureContext context, int depth) {
        List<Object> result = new ArrayList<>(Math.min(CaptureLimits.MAX_COLLECTION_ELEMENTS + 1, 16));
        Iterator<?> values = collection.iterator();
        int retained = 0;
        while (retained < CaptureLimits.MAX_COLLECTION_ELEMENTS && values.hasNext()) {
            if (!context.claimEntry()) {
                result.add(BUDGET_MARKER);
                return Collections.unmodifiableList(result);
            }
            Object value = values.next();
            result.add(capture(value, context, depth + 1));
            retained++;
        }
        if (values.hasNext()) {
            values.next();
            context.markTruncated();
            result.add("[additional items omitted]");
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean requiresGraphTracking(Object value) {
        return value.getClass().isArray() || value instanceof Map<?, ?> || value instanceof Collection<?>;
    }

    private static String captureRendered(Object value, CaptureContext context) {
        int maximum = Math.min(CaptureLimits.MAX_TEXT_CHARS, context.remainingCharacters());
        return context.captureText(MessageFormatter.safeToString(value, maximum), maximum);
    }

    private static String omission(int omitted, String noun) {
        return "[" + omitted + ' ' + noun + (omitted == 1 ? "" : "s") + " omitted]";
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
}
