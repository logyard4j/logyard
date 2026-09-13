package com.logyard4j.logyard.api.event;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;

/** Captures caller-owned scalar values before asynchronous delivery. */
final class ValueCapture {
    private static final Object[] EMPTY_ARGUMENTS = new Object[0];
    static final String BUDGET_MARKER = "[event capture budget exhausted]";

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

    static Object capture(Object value, CaptureContext context, int depth) {
        if (value == null || isClosedScalar(value)) {
            return value;
        }
        if (value instanceof String string) {
            return context.capturePayloadText(string, CaptureLimits.MAX_TEXT_CHARS);
        }
        if (ValueContainerCapture.supports(value)) {
            return ValueContainerCapture.capture(value, context, depth);
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

    private static String captureRendered(Object value, CaptureContext context) {
        int maximum = Math.min(CaptureLimits.MAX_TEXT_CHARS, context.remainingPayloadCharacters());
        MessageFormatter.RenderResult rendered = MessageFormatter.safeRender(value, maximum);
        if (rendered.truncated()) {
            context.markTruncated();
        }
        return context.capturePayloadText(rendered.value(), maximum);
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
