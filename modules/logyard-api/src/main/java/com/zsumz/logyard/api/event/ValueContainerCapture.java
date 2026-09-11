package com.zsumz.logyard.api.event;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Captures caller-owned arrays, maps, and collections with shared graph budgets. */
final class ValueContainerCapture {
    private static final String DEPTH_MARKER = "[maximum nesting depth reached]";
    private static final String CYCLE_MARKER = "[circular reference]";
    private static final String SHARED_MARKER = "[shared reference]";

    private ValueContainerCapture() {
    }

    static boolean supports(Object value) {
        return value.getClass().isArray() || value instanceof Map<?, ?> || value instanceof Collection<?>;
    }

    static Object capture(Object value, CaptureContext context, int depth) {
        if (depth >= CaptureLimits.MAX_NESTING_DEPTH) {
            context.markTruncated();
            return DEPTH_MARKER;
        }
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
            return ValueCapture.BUDGET_MARKER;
        }
        try {
            return captureContents(value, context, depth);
        } finally {
            context.leaveValue(value);
        }
    }

    private static Object captureContents(Object value, CaptureContext context, int depth) {
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
            result.add(ValueCapture.capture(Array.get(array, index), context, depth + 1));
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
                result.put(uniqueTruncationKey(result), ValueCapture.BUDGET_MARKER);
                return Collections.unmodifiableMap(result);
            }
            Map.Entry<?, ?> entry = entries.next();
            if (context.remainingPayloadCharacters() == 0) {
                context.markTruncated();
                result.put(uniqueTruncationKey(result), ValueCapture.BUDGET_MARKER);
                return Collections.unmodifiableMap(result);
            }
            String renderedKey = renderMapKey(entry.getKey(), context);
            String key = CapturedMapKeys.resolve(entry.getKey(), renderedKey, result, context);
            if (key == null) {
                result.put(uniqueTruncationKey(result), ValueCapture.BUDGET_MARKER);
                return Collections.unmodifiableMap(result);
            }
            result.put(key, ValueCapture.capture(entry.getValue(), context, depth + 1));
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
                result.add(ValueCapture.BUDGET_MARKER);
                return Collections.unmodifiableList(result);
            }
            result.add(ValueCapture.capture(values.next(), context, depth + 1));
            retained++;
        }
        if (values.hasNext()) {
            values.next();
            context.markTruncated();
            result.add("[additional items omitted]");
        }
        return Collections.unmodifiableList(result);
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
        String key = SystemAttributes.VALUE_TRUNCATED;
        int suffix = 1;
        while (values.containsKey(key)) {
            key = SystemAttributes.VALUE_TRUNCATED + "." + suffix++;
        }
        return key;
    }
}
