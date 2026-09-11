package com.logyard4j.api.event;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;

/** Detaches one caller-owned JDK formatter argument into Logyard's closed bounded value set. */
final class FormatArgumentCapture {
    private FormatArgumentCapture() {
    }

    static Captured capture(Object value) {
        if (value == null || value instanceof Boolean || value instanceof Character || value instanceof Byte
                || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof Float
                || value instanceof Double) {
            return new Captured(value, false);
        }
        if (value instanceof String string) {
            String bounded = CaptureLimits.truncate(string, CaptureLimits.MAX_CAPTURED_NUMBER_CHARS);
            return new Captured(bounded, bounded != string);
        }
        if (value.getClass() == BigInteger.class) {
            Object bounded = SafeNumberCapture.bigInteger((BigInteger) value);
            return new Captured(bounded instanceof BigInteger ? bounded : bounded.toString(), !(bounded instanceof BigInteger));
        }
        if (value.getClass() == BigDecimal.class) {
            return captureDecimal((BigDecimal) value);
        }
        if (value.getClass() == Date.class) {
            return new Captured(CapturedTemporal.from((Date) value), false);
        }
        MessageFormatter.RenderResult rendered =
                MessageFormatter.safeRender(value, CaptureLimits.MAX_CAPTURED_NUMBER_CHARS);
        return new Captured(rendered.value(), rendered.truncated());
    }

    private static Captured captureDecimal(BigDecimal value) {
        if (value.precision() > CaptureLimits.MAX_CAPTURED_NUMBER_CHARS
                || Math.abs((long) value.scale()) > CaptureLimits.MAX_CAPTURED_NUMBER_CHARS) {
            return new Captured(
                    MessageFormatter.safeRender(value, CaptureLimits.MAX_CAPTURED_NUMBER_CHARS).value(),
                    true);
        }
        Object bounded = SafeNumberCapture.bigDecimal(value);
        return new Captured(bounded instanceof BigDecimal ? bounded : bounded.toString(), !(bounded instanceof BigDecimal));
    }

    record Captured(Object value, boolean truncated) {
    }
}
