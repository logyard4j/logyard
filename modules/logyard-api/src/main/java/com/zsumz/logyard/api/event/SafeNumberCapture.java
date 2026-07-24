package com.zsumz.logyard.api.event;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Converts arbitrary-precision numbers into detached, bounded, JSON-safe values. */
final class SafeNumberCapture {
    private static final String OMITTED_PREFIX = "[numeric value omitted: approximately ";

    private SafeNumberCapture() {
    }

    static Object bigInteger(BigInteger value) {
        int estimatedDigits = decimalDigits(value);
        if (estimatedDigits + (value.signum() < 0 ? 1 : 0) > CaptureLimits.MAX_CAPTURED_NUMBER_CHARS) {
            return omission(estimatedDigits);
        }
        String representation = value.toString();
        if (representation.length() > CaptureLimits.MAX_CAPTURED_NUMBER_CHARS) {
            return omission(representation.length());
        }
        return new BigInteger(representation);
    }

    static Object bigInteger(BigInteger value, CaptureContext context) {
        return charge(bigInteger(value), context);
    }

    static Object bigDecimal(BigDecimal value) {
        int estimatedCharacters = decimalDigits(value.unscaledValue()) + 16;
        if (estimatedCharacters > CaptureLimits.MAX_CAPTURED_NUMBER_CHARS) {
            return omission(estimatedCharacters - 16);
        }
        String representation = value.toString();
        if (representation.length() > CaptureLimits.MAX_CAPTURED_NUMBER_CHARS) {
            return omission(representation.length());
        }
        return new BigDecimal(representation);
    }

    static Object bigDecimal(BigDecimal value, CaptureContext context) {
        return charge(bigDecimal(value), context);
    }

    private static int decimalDigits(BigInteger value) {
        int bits = value.abs().bitLength();
        if (bits == 0) {
            return 1;
        }
        return (int) Math.min(Integer.MAX_VALUE, ((long) bits * 1_233L >>> 12) + 1L);
    }

    private static String omission(int characters) {
        return OMITTED_PREFIX + characters + " digits]";
    }

    private static Object charge(Object captured, CaptureContext context) {
        String representation = captured.toString();
        if (captured instanceof String) {
            context.markTruncated();
            return context.capturePayloadText(representation, CaptureLimits.MAX_CAPTURED_NUMBER_CHARS);
        }
        if (representation.length() > context.remainingPayloadCharacters()) {
            context.markTruncated();
            return context.capturePayloadText(omission(representation.length()), CaptureLimits.MAX_CAPTURED_NUMBER_CHARS);
        }
        context.capturePayloadText(representation, representation.length());
        return captured;
    }
}
