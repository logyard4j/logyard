package com.logyard4j.logyard.api.event;

import java.util.Objects;

/** Append-only formatting target that aborts a formatter as soon as its output allowance is exhausted. */
final class BoundedFormatBuffer implements Appendable {
    private final StringBuilder content;
    private final int maximum;

    BoundedFormatBuffer(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be positive");
        }
        this.maximum = maximum;
        content = new StringBuilder(Math.min(maximum, 256));
    }

    @Override
    public BoundedFormatBuffer append(CharSequence value) {
        CharSequence safe = value == null ? "null" : value;
        return append(safe, 0, safe.length());
    }

    @Override
    public BoundedFormatBuffer append(CharSequence value, int start, int end) {
        CharSequence safe = value == null ? "null" : value;
        Objects.checkFromToIndex(start, end, safe.length());
        int requested = end - start;
        if (requested <= maximum - content.length()) {
            content.append(safe, start, end);
            return this;
        }
        appendTruncated(safe, start, end);
        throw LimitReached.INSTANCE;
    }

    @Override
    public BoundedFormatBuffer append(char value) {
        if (content.length() < maximum) {
            content.append(value);
            return this;
        }
        replaceTailWithMarker();
        throw LimitReached.INSTANCE;
    }

    String value() {
        return content.toString();
    }

    private void appendTruncated(CharSequence value, int start, int end) {
        int payloadEnd = Math.min(end, start + Math.max(0, maximum - content.length() - 1));
        if (payloadEnd > start && payloadEnd < end && Character.isHighSurrogate(value.charAt(payloadEnd - 1))
                && Character.isLowSurrogate(value.charAt(payloadEnd))) {
            payloadEnd--;
        }
        content.append(value, start, payloadEnd);
        if (content.length() < maximum) {
            content.append('…');
        } else {
            replaceTailWithMarker();
        }
    }

    private void replaceTailWithMarker() {
        int markerIndex = content.length() - 1;
        if (markerIndex > 0 && Character.isLowSurrogate(content.charAt(markerIndex))
                && Character.isHighSurrogate(content.charAt(markerIndex - 1))) {
            markerIndex--;
        }
        content.setLength(Math.max(0, markerIndex));
        content.append('…');
    }

    static final class LimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private static final LimitReached INSTANCE = new LimitReached();

        private LimitReached() {
            super("formatter output allowance exhausted", null, false, false);
        }
    }
}
