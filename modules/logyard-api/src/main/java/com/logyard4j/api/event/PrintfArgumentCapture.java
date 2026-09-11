package com.logyard4j.api.event;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/** Captures only the caller behavior required by each parsed printf conversion. */
final class PrintfArgumentCapture {
    private PrintfArgumentCapture() {
    }

    static CapturedArguments capture(
            List<BoundedPrintfPattern.ArgumentRequest> requests,
            Object[] parameters,
            Locale locale,
            ZoneId zone) {
        Object[] captured = new Object[requests.size()];
        boolean truncated = false;
        for (int index = 0; index < requests.size(); index++) {
            BoundedPrintfPattern.ArgumentRequest request = requests.get(index);
            Object source = parameters[request.argumentIndex()];
            FormatArgumentCapture.Captured value;
            switch (request.kind()) {
                case DISPLAY, TYPED -> value = FormatArgumentCapture.capture(source);
                case HASH -> value = captureHash(source);
                case BOOLEAN -> value = captureBoolean(source);
                case TEMPORAL -> value = captureTemporal(source, request.temporalConversion(), locale, zone);
                default -> throw new IllegalStateException("unknown printf capture kind " + request.kind());
            }
            captured[index] = value.value();
            truncated |= value.truncated();
        }
        return new CapturedArguments(captured, truncated);
    }

    private static FormatArgumentCapture.Captured captureHash(Object value) {
        return new FormatArgumentCapture.Captured(
                value == null ? null : new CapturedHash(value.hashCode()),
                false);
    }

    private static FormatArgumentCapture.Captured captureBoolean(Object value) {
        Object captured = value == null || value instanceof Boolean ? value : Boolean.TRUE;
        return new FormatArgumentCapture.Captured(captured, false);
    }

    private static FormatArgumentCapture.Captured captureTemporal(
            Object value,
            char conversion,
            Locale locale,
            ZoneId zone) {
        FormatArgumentCapture.Captured captured = FormatArgumentCapture.capture(value);
        String rendered = TrustedDateTimeRenderer.printf(captured.value(), conversion, locale, zone);
        return new FormatArgumentCapture.Captured(rendered, captured.truncated());
    }

    record CapturedArguments(Object[] values, boolean truncated) {
    }

    private record CapturedHash(int value) {
        @Override
        public int hashCode() {
            return value;
        }
    }
}
