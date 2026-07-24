package com.zsumz.logyard.api.event;

/** Caps printf width and precision before {@link java.util.Formatter} sees the pattern. */
final class BoundedPrintfPattern {
    private BoundedPrintfPattern() {
    }

    static String capture(String pattern, int maximumFieldWidth) {
        StringBuilder bounded = new StringBuilder(pattern.length());
        int cursor = 0;
        while (cursor < pattern.length()) {
            char current = pattern.charAt(cursor++);
            bounded.append(current);
            if (current != '%' || cursor >= pattern.length() || pattern.charAt(cursor) == '%'
                    || pattern.charAt(cursor) == 'n') {
                continue;
            }
            cursor = copyArgumentIndex(pattern, cursor, bounded);
            while (cursor < pattern.length() && isFlag(pattern.charAt(cursor))) {
                bounded.append(pattern.charAt(cursor++));
            }
            cursor = copyBoundedNumber(pattern, cursor, bounded, maximumFieldWidth);
            if (cursor < pattern.length() && pattern.charAt(cursor) == '.') {
                bounded.append('.');
                cursor = copyBoundedNumber(pattern, cursor + 1, bounded, maximumFieldWidth);
            }
            if (cursor < pattern.length() && (pattern.charAt(cursor) == 't' || pattern.charAt(cursor) == 'T')) {
                bounded.append(pattern.charAt(cursor++));
            }
            if (cursor < pattern.length()) {
                bounded.append(pattern.charAt(cursor++));
            }
        }
        return bounded.toString();
    }

    private static int copyArgumentIndex(String pattern, int cursor, StringBuilder target) {
        int digitsEnd = digitEnd(pattern, cursor);
        if (digitsEnd < pattern.length() && digitsEnd > cursor && pattern.charAt(digitsEnd) == '$') {
            target.append(pattern, cursor, digitsEnd + 1);
            return digitsEnd + 1;
        }
        return cursor;
    }

    private static int copyBoundedNumber(String pattern, int cursor, StringBuilder target, int maximum) {
        int end = digitEnd(pattern, cursor);
        if (end == cursor) {
            return cursor;
        }
        long value = 0L;
        for (int index = cursor; index < end && value <= maximum; index++) {
            value = value * 10L + pattern.charAt(index) - '0';
        }
        target.append(Math.min(value, maximum));
        return end;
    }

    private static int digitEnd(String pattern, int cursor) {
        int end = cursor;
        while (end < pattern.length() && Character.isDigit(pattern.charAt(end))) {
            end++;
        }
        return end;
    }

    private static boolean isFlag(char value) {
        return "-#+ 0,(<".indexOf(value) >= 0;
    }
}
