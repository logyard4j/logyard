package com.logyard4j.api.event;

/** Incrementally appends text without exceeding one configured UTF-16 character allowance. */
final class BoundedText {
    private final int maximum;
    private final StringBuilder value;
    private boolean truncated;

    BoundedText(int maximum) {
        this.maximum = Math.max(0, maximum);
        value = new StringBuilder(Math.min(this.maximum, 128));
    }

    BoundedText append(CharSequence source) {
        return append(source, 0, source.length());
    }

    BoundedText append(CharSequence source, int start, int end) {
        int available = maximum - value.length();
        int length = end - start;
        if (length <= available) {
            value.append(source, start, end);
        } else {
            int copied = Math.max(0, available);
            if (splitsSurrogatePair(source, start, end, copied)) {
                copied--;
            }
            value.append(source, start, start + copied);
            truncated = true;
        }
        return this;
    }

    BoundedText append(char character) {
        if (value.length() < maximum) {
            value.append(character);
        } else {
            truncated = true;
        }
        return this;
    }

    BoundedText append(int number) {
        return append(Integer.toString(number));
    }

    boolean full() {
        return value.length() >= maximum;
    }

    MessageFormatter.RenderResult result() {
        if (truncated && maximum > 0) {
            appendTruncationMarker();
        }
        return new MessageFormatter.RenderResult(value.toString(), truncated);
    }

    private static boolean splitsSurrogatePair(CharSequence source, int start, int end, int copied) {
        return copied > 0
                && start + copied < end
                && Character.isHighSurrogate(source.charAt(start + copied - 1))
                && Character.isLowSurrogate(source.charAt(start + copied));
    }

    private void appendTruncationMarker() {
        if (value.length() == maximum) {
            int last = value.length() - 1;
            if (last > 0 && Character.isLowSurrogate(value.charAt(last)) && Character.isHighSurrogate(value.charAt(last - 1))) {
                value.deleteCharAt(last);
                last--;
            }
            value.setCharAt(last, '…');
        } else {
            value.append('…');
        }
    }
}
