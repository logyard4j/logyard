package com.zsumz.logyard.api.event;

/** Caps printf width and precision while identifying exactly which caller arguments the formatter can consult. */
final class BoundedPrintfPattern {
    private BoundedPrintfPattern() {
    }

    static Analysis analyze(String pattern, int maximumFieldWidth, int parameterCount, int capturedParameterCount) {
        StringBuilder bounded = new StringBuilder(pattern.length());
        boolean[] referenced = new boolean[capturedParameterCount];
        boolean referencedParameterOmitted = false;
        int ordinaryArgument = 0;
        int previousArgument = -1;
        int cursor = 0;
        while (cursor < pattern.length()) {
            char current = pattern.charAt(cursor++);
            bounded.append(current);
            if (current != '%' || cursor >= pattern.length()) {
                continue;
            }
            if (pattern.charAt(cursor) == '%' || pattern.charAt(cursor) == 'n') {
                bounded.append(pattern.charAt(cursor++));
                continue;
            }

            ArgumentIndex explicit = argumentIndex(pattern, cursor);
            if (explicit.present()) {
                bounded.append(pattern, cursor, explicit.end());
                cursor = explicit.end();
            }
            boolean reusePrevious = false;
            while (cursor < pattern.length() && isFlag(pattern.charAt(cursor))) {
                char flag = pattern.charAt(cursor++);
                bounded.append(flag);
                reusePrevious |= flag == '<';
            }
            cursor = copyBoundedNumber(pattern, cursor, bounded, maximumFieldWidth);
            if (cursor < pattern.length() && pattern.charAt(cursor) == '.') {
                bounded.append('.');
                cursor = copyBoundedNumber(pattern, cursor + 1, bounded, maximumFieldWidth);
            }
            if (cursor < pattern.length() && (pattern.charAt(cursor) == 't' || pattern.charAt(cursor) == 'T')) {
                bounded.append(pattern.charAt(cursor++));
            }
            char conversion = cursor < pattern.length() ? pattern.charAt(cursor++) : '\0';
            if (conversion != '\0') {
                bounded.append(conversion);
            }

            if (conversion == '%' || conversion == 'n') {
                continue;
            }

            int selected;
            if (reusePrevious) {
                selected = previousArgument;
            } else if (explicit.present()) {
                selected = explicit.zeroBased();
            } else {
                selected = ordinaryArgument++;
            }
            if (selected >= 0) {
                previousArgument = selected;
                if (selected < referenced.length) {
                    referenced[selected] = true;
                } else if (selected < parameterCount) {
                    referencedParameterOmitted = true;
                }
            }
        }
        return new Analysis(bounded.toString(), referenced, referencedParameterOmitted);
    }

    record Analysis(String pattern, boolean[] referenced, boolean referencedParameterOmitted) {
        boolean referenced(int index) {
            return referenced[index];
        }
    }

    private static ArgumentIndex argumentIndex(String pattern, int cursor) {
        int digitsEnd = digitEnd(pattern, cursor);
        if (digitsEnd >= pattern.length() || digitsEnd == cursor || pattern.charAt(digitsEnd) != '$') {
            return ArgumentIndex.NONE;
        }
        int value = 0;
        for (int index = cursor; index < digitsEnd; index++) {
            int digit = pattern.charAt(index) - '0';
            if (value > (Integer.MAX_VALUE - digit) / 10) {
                return new ArgumentIndex(-1, digitsEnd + 1);
            }
            value = value * 10 + digit;
        }
        return new ArgumentIndex(value - 1, digitsEnd + 1);
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

    private record ArgumentIndex(int zeroBased, int end) {
        private static final ArgumentIndex NONE = new ArgumentIndex(-1, -1);

        boolean present() {
            return end >= 0;
        }
    }
}
