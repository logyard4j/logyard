package com.logyard4j.logyard.api.event;

import com.logyard4j.logyard.api.failure.FailureIsolation;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/** Renders one value graph within the event traversal and text budgets. */
final class SafeValueRenderer {
    private SafeValueRenderer() {
    }

    static void append(BoundedText result, Object value) {
        append(result, value, new RenderState(), 0);
    }

    static void append(BoundedText result, Object value, RenderState state, int depth) {
        if (value == null) {
            result.append("null");
            return;
        }
        if (!structured(value)) {
            appendObject(result, value);
            return;
        }
        if (depth >= CaptureLimits.MAX_NESTING_DEPTH) {
            result.append("[maximum nesting depth reached]");
            return;
        }
        if (!state.firstVisit(value)) {
            result.append("[shared reference]");
            return;
        }
        appendStructured(result, value, state, depth);
    }

    private static boolean structured(Object value) {
        return value.getClass().isArray() || value instanceof Map<?, ?> || value instanceof Iterable<?>;
    }

    private static void appendStructured(BoundedText result, Object value, RenderState state, int depth) {
        if (value.getClass().isArray()) {
            appendArray(result, value, state, depth);
        } else if (value instanceof Map<?, ?> map) {
            appendMap(result, map, state, depth);
        } else {
            appendIterable(result, (Iterable<?>) value, state, depth);
        }
    }

    private static void appendArray(BoundedText result, Object array, RenderState state, int depth) {
        result.append('[');
        int sourceLength = Array.getLength(array);
        int length = Math.min(sourceLength, CaptureLimits.MAX_COLLECTION_ELEMENTS);
        for (int index = 0; index < length && !result.full(); index++) {
            if (!state.claimEntry()) {
                appendExhaustedBudgetMarker(result, index);
                break;
            }
            appendSeparator(result, index);
            append(result, Array.get(array, index), state, depth + 1);
        }
        if (sourceLength > length) {
            result.append(", ... ").append(sourceLength - length).append(" element(s) omitted");
        }
        result.append(']');
    }

    private static void appendMap(BoundedText result, Map<?, ?> map, RenderState state, int depth) {
        result.append('{');
        Iterator<? extends Map.Entry<?, ?>> entries = map.entrySet().iterator();
        int index = 0;
        while (index < CaptureLimits.MAX_COLLECTION_ELEMENTS && entries.hasNext() && !result.full()) {
            if (!state.claimEntry()) {
                appendExhaustedBudgetMarker(result, index);
                break;
            }
            Map.Entry<?, ?> entry = entries.next();
            appendSeparator(result, index++);
            append(result, entry.getKey(), state, depth + 1);
            result.append('=');
            append(result, entry.getValue(), state, depth + 1);
        }
        if (entries.hasNext()) {
            result.append(", ...");
        }
        result.append('}');
    }

    private static void appendIterable(BoundedText result, Iterable<?> iterable, RenderState state, int depth) {
        result.append('[');
        Iterator<?> values = iterable.iterator();
        int index = 0;
        while (index < CaptureLimits.MAX_COLLECTION_ELEMENTS && values.hasNext() && !result.full()) {
            if (!state.claimEntry()) {
                appendExhaustedBudgetMarker(result, index);
                break;
            }
            Object value = values.next();
            appendSeparator(result, index++);
            append(result, value, state, depth + 1);
        }
        if (values.hasNext()) {
            result.append(", ...");
        }
        result.append(']');
    }

    private static void appendSeparator(BoundedText result, int index) {
        if (index > 0) {
            result.append(", ");
        }
    }

    private static void appendExhaustedBudgetMarker(BoundedText result, int index) {
        appendSeparator(result, index);
        result.append("[render traversal budget exhausted]");
    }

    private static void appendObject(BoundedText result, Object value) {
        try {
            if (value instanceof Enum<?> enumeration) {
                result.append(enumeration.name());
            } else if (value.getClass() == BigInteger.class) {
                result.append(String.valueOf(SafeNumberCapture.bigInteger((BigInteger) value)));
            } else if (value.getClass() == BigDecimal.class) {
                result.append(String.valueOf(SafeNumberCapture.bigDecimal((BigDecimal) value)));
            } else if (value.getClass() == java.util.Date.class) {
                result.append(CapturedTemporal.from((java.util.Date) value).toString());
            } else if (value instanceof CharSequence sequence) {
                result.append(sequence);
            } else {
                result.append(String.valueOf(value));
            }
        } catch (Throwable failure) {
            FailureIsolation.prepareForRecovery(failure);
            result.append("[FAILED toString(): ").append(failure.getClass().getSimpleName()).append(']');
        }
    }

    static final class RenderState {
        private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        private int remainingEntries = CaptureLimits.MAX_EVENT_ENTRIES;

        private boolean firstVisit(Object value) {
            return seen.put(value, Boolean.TRUE) == null;
        }

        private boolean claimEntry() {
            if (remainingEntries == 0) {
                return false;
            }
            remainingEntries--;
            return true;
        }
    }
}
