package com.zsumz.logyard.api.event;

import java.util.Date;

/** Conservatively rejects MessageFormat inputs whose substitutions could amplify into a large intermediate string. */
final class MessageFormatWorkBudget {
    private static final int DEFAULT_FORMATTED_VALUE_CHARS = 128;

    private MessageFormatWorkBudget() {
    }

    static boolean permits(String pattern, Object[] parameters) {
        long estimate = pattern.length();
        boolean quoted = false;
        for (int cursor = 0; cursor < pattern.length(); cursor++) {
            char current = pattern.charAt(cursor);
            if (current == '\'') {
                if (cursor + 1 < pattern.length() && pattern.charAt(cursor + 1) == '\'') {
                    cursor++;
                } else {
                    quoted = !quoted;
                }
                continue;
            }
            if (!quoted && current == '{') {
                int argumentIndex = argumentIndex(pattern, cursor + 1);
                if (argumentIndex >= 0 && argumentIndex < parameters.length) {
                    estimate += formattedLength(parameters[argumentIndex]);
                    if (estimate > CaptureLimits.MAX_FORMATTER_WORK_CHARS) {
                        return false;
                    }
                }
            }
        }
        return estimate <= CaptureLimits.MAX_FORMATTER_WORK_CHARS;
    }

    private static int argumentIndex(String pattern, int cursor) {
        while (cursor < pattern.length() && Character.isWhitespace(pattern.charAt(cursor))) {
            cursor++;
        }
        int start = cursor;
        int value = 0;
        while (cursor < pattern.length() && Character.isDigit(pattern.charAt(cursor))) {
            int digit = pattern.charAt(cursor++) - '0';
            if (value > (Integer.MAX_VALUE - digit) / 10) {
                return -1;
            }
            value = value * 10 + digit;
        }
        return cursor == start ? -1 : value;
    }

    private static int formattedLength(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof CharSequence sequence) {
            return sequence.length();
        }
        if (value instanceof Character) {
            return 1;
        }
        if (value instanceof Boolean) {
            return 5;
        }
        if (value instanceof Date) {
            return DEFAULT_FORMATTED_VALUE_CHARS;
        }
        return Math.max(DEFAULT_FORMATTED_VALUE_CHARS, String.valueOf(value).length());
    }
}
