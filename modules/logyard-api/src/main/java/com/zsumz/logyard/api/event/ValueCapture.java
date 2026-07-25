package com.zsumz.logyard.api.event;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;

/** Captures caller-owned values as bounded trees before asynchronous delivery. */
final class ValueCapture {
    private static final Object[] EMPTY_ARGUMENTS = new Object[0];
    private static final String DEPTH_MARKER = "[maximum nesting depth reached]";
    private static final String CYCLE_MARKER = "[circular reference]";
    private static final String SHARED_MARKER = "[shared reference]";
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
        if (value == null || isClosedScalar(value)) {
            return value;
        }
        if (value instanceof String string) {
            return context.capturePayloadText(string, CaptureLimits.MAX_TEXT_CHARS);
        }

        boolean container = requiresGraphTracking(value);
        if (container && depth >= CaptureLimits.MAX_NESTING_DEPTH) {
            context.markTruncated();
            return DEPTH_MARKER;
        }
        if (container) {
            return captureContainerReference(value, context, depth);
        }

        Object completed = context.capturedScalar(value);
        if (completed != null) {
            return completed;
        }
        if (!context.claimNode()) {
            return BUDGET_MARKER;
        }
        if (context.remainingPayloadCharacters() == 0) {
            context.markTruncated();
            return BUDGET_MARKER;
        }

        Object captured = captureScalar(value, context);
        context.completeScalar(value, captured);
        return captured;
    }

    private static Object captureContainerReference(Object value, CaptureContext context, int depth) {
        CaptureContext.ReferenceState reference = context.enterValue(value);
        if (reference == CaptureContext.ReferenceState.CYCLE) {
            context.markTruncated();
            return CYCLE_MARKER;
        }
        if (reference == CaptureContext.ReferenceState.SHARED) {
            context.markTruncated();
            return SHARED_MARKER;
        }
        if (!context.claimNode()) {
            context.leaveValue(value);
            return BUDGET_MARKER;
        }
        try {
            return captureContainer(value, context, depth);
        } finally {
            context.leaveValue(value);
        }
    }

    private static Object captureScalar(Object value, CaptureContext context) {
        if (value instanceof Enum<?> enumeration) {
            return context.capturePayloadText(enumeration.name(), CaptureLimits.MAX_TEXT_CHARS);
        }
        if (value.getClass() == BigInteger.class) {
            return SafeNumberCapture.bigInteger((BigInteger) value, context);
        }
        if (value.getClass() == BigDecimal.class) {
            return SafeNumberCapture.bigDecimal((BigDecimal) value, context);
        }
        if (value.getClass() == Date.class) {
            return context.capturePayloadText(
                    CapturedTemporal.from((Date) value).toString(),
                    CaptureLimits.MAX_TEXT_CHARS);
        }
        return captureRendered(value, context);
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
            if (context.remainingPayloadCharacters() == 0) {
                context.markTruncated();
                result.put(uniqueTruncationKey(result), BUDGET_MARKER);
                return Collections.unmodifiableMap(result);
            }
            String renderedKey = renderMapKey(entry.getKey(), context);
            String key = CapturedMapKeys.resolve(entry.getKey(), renderedKey, result, context);
            if (key == null) {
                result.put(uniqueTruncationKey(result), BUDGET_MARKER);
                return Collections.unmodifiableMap(result);
            }
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
            result.add(capture(values.next(), context, depth + 1));
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
        int maximum = Math.min(CaptureLimits.MAX_TEXT_CHARS, context.remainingPayloadCharacters());
        MessageFormatter.RenderResult rendered = MessageFormatter.safeRender(value, maximum);
        if (rendered.truncated()) {
            context.markTruncated();
        }
        return context.capturePayloadText(rendered.value(), maximum);
    }

    private static String renderMapKey(Object key, CaptureContext context) {
        int maximum = Math.min(CaptureLimits.MAX_TEXT_CHARS, context.remainingPayloadCharacters());
        MessageFormatter.RenderResult rendered = MessageFormatter.safeRender(key, maximum);
        if (rendered.truncated()) {
            context.markTruncated();
        }
        return rendered.value();
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

    private static boolean isClosedScalar(Object value) {
        return value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof Character;
    }
}
