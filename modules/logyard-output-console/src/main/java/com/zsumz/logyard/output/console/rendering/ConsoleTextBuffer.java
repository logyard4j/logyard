package com.zsumz.logyard.output.console.rendering;

/** Hard-bounded console text buffer with incremental control-character sanitization. */
final class ConsoleTextBuffer {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final int maximum;
    private final StringBuilder value;
    private boolean truncated;

    ConsoleTextBuffer(int maximum) {
        this.maximum = maximum;
        value = new StringBuilder(Math.min(maximum, 128));
    }

    ConsoleTextBuffer append(char character) {
        if (value.length() < maximum) {
            value.append(character);
        } else {
            truncated = true;
        }
        return this;
    }

    ConsoleTextBuffer append(String text) {
        int available = maximum - value.length();
        if (text.length() <= available) {
            value.append(text);
        } else {
            int end = Math.max(0, available);
            if (end > 0 && end < text.length()
                    && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            value.append(text, 0, end);
            truncated = true;
        }
        return this;
    }

    void appendSanitized(String text) {
        int index = 0;
        while (index < text.length() && !full()) {
            char character = text.charAt(index);
            switch (character) {
                case '\n' -> append("\\n");
                case '\r' -> append("\\r");
                case '\t' -> append("\\t");
                case '\b' -> append("\\b");
                case '\f' -> append("\\f");
                default -> {
                    if (ConsoleText.isUnsafeControl(character)) {
                        append("\\u");
                        append(HEX[(character >>> 12) & 0xf]);
                        append(HEX[(character >>> 8) & 0xf]);
                        append(HEX[(character >>> 4) & 0xf]);
                        append(HEX[character & 0xf]);
                    } else {
                        append(character);
                    }
                }
            }
            index++;
        }
        if (index < text.length()) {
            truncated = true;
        }
    }

    boolean full() {
        return value.length() >= maximum;
    }

    int remaining() {
        return maximum - value.length();
    }

    String finish() {
        if (truncated && !value.isEmpty()) {
            int last = value.length() - 1;
            if (last > 0 && Character.isLowSurrogate(value.charAt(last)) && Character.isHighSurrogate(value.charAt(last - 1))) {
                value.deleteCharAt(last);
                last--;
            }
            value.setCharAt(last, '…');
        }
        return value.toString();
    }
}
