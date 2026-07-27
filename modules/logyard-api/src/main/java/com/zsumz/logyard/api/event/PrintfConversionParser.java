package com.zsumz.logyard.api.event;

import java.util.DuplicateFormatFlagsException;
import java.util.IllegalFormatPrecisionException;

/** Parses one printf conversion without selecting or capturing its argument. */
final class PrintfConversionParser {
    private PrintfConversionParser() {
    }

    static Conversion parse(String pattern, int cursor, int maximumFieldWidth) {
        ArgumentIndex explicit = argumentIndex(pattern, cursor);
        int explicitStart = cursor;
        if (explicit.present()) {
            cursor = explicit.end();
        }

        StringBuilder flags = new StringBuilder();
        boolean reusePrevious = false;
        while (cursor < pattern.length() && isFlag(pattern.charAt(cursor))) {
            char flag = pattern.charAt(cursor++);
            if (flag == '<') {
                if (reusePrevious) {
                    throw new DuplicateFormatFlagsException("<");
                }
                reusePrevious = true;
            } else {
                flags.append(flag);
            }
        }

        BoundedNumber width = boundedNumber(pattern, cursor, maximumFieldWidth);
        cursor = width.end();
        boolean precisionPresent = cursor < pattern.length() && pattern.charAt(cursor) == '.';
        BoundedNumber precision = precisionPresent
                ? boundedNumber(pattern, cursor + 1, maximumFieldWidth)
                : BoundedNumber.absent(cursor);
        if (precisionPresent) {
            cursor = precision.end();
        }
        char datePrefix = cursor < pattern.length() && (pattern.charAt(cursor) == 't' || pattern.charAt(cursor) == 'T')
                ? pattern.charAt(cursor++)
                : '\0';
        char conversion = cursor < pattern.length() ? pattern.charAt(cursor++) : '\0';
        if (datePrefix != '\0' && precisionPresent && !precision.text().isEmpty()) {
            throw new IllegalFormatPrecisionException(Integer.parseInt(precision.text()));
        }
        return new Conversion(cursor, explicitStart, explicit, flags.toString(), reusePrevious, width, precisionPresent, precision, datePrefix, conversion);
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

    private static BoundedNumber boundedNumber(String pattern, int cursor, int maximum) {
        int end = digitEnd(pattern, cursor);
        if (end == cursor) {
            return BoundedNumber.absent(cursor);
        }
        long value = 0L;
        for (int index = cursor; index < end && value <= maximum; index++) {
            value = value * 10L + pattern.charAt(index) - '0';
        }
        return new BoundedNumber(Long.toString(Math.min(value, maximum)), end, value > maximum);
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

    record Conversion(
            int end,
            int explicitStart,
            ArgumentIndex explicit,
            String flags,
            boolean reusePrevious,
            BoundedNumber width,
            boolean precisionPresent,
            BoundedNumber precision,
            char datePrefix,
            char conversion) {
        boolean passThrough() {
            return conversion == '%' || conversion == 'n' || conversion == '\0';
        }
    }

    record ArgumentIndex(int zeroBased, int end) {
        static final ArgumentIndex NONE = new ArgumentIndex(-1, -1);

        boolean present() {
            return end >= 0;
        }
    }

    record BoundedNumber(String text, int end, boolean changed) {
        static BoundedNumber absent(int cursor) {
            return new BoundedNumber("", cursor, false);
        }
    }
}
