package com.logyard4j.logyard.api.event;

/** Produces bounded UTF-16 slices without separating a surrogate pair. */
final class SurrogateSafeText {
    private SurrogateSafeText() {
    }

    static String truncate(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
        }
        if (maximum <= 0) {
            return "";
        }
        return prefix(value, maximum - 1) + '…';
    }

    static String prefix(String value, int requestedLength) {
        int length = Math.max(0, Math.min(value.length(), requestedLength));
        if (length > 0 && length < value.length()
                && Character.isHighSurrogate(value.charAt(length - 1))
                && Character.isLowSurrogate(value.charAt(length))) {
            length--;
        }
        return value.substring(0, length);
    }

    static String suffix(String value, int requestedLength) {
        int start = Math.max(0, value.length() - Math.max(0, requestedLength));
        if (start > 0 && start < value.length()
                && Character.isHighSurrogate(value.charAt(start - 1))
                && Character.isLowSurrogate(value.charAt(start))) {
            start++;
        }
        return value.substring(start);
    }
}
