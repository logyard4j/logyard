package com.zsumz.logyard.api.event;

import java.time.ZoneId;
import java.util.DuplicateFormatFlagsException;
import java.util.IllegalFormatPrecisionException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses printf conversions into bounded syntax and conversion-specific captured arguments. */
final class BoundedPrintfPattern {
    private static final int MISSING_ARGUMENT_INDEX = 9_999;

    private BoundedPrintfPattern() {
    }

    static Analysis analyze(String pattern, int maximumFieldWidth, int parameterCount, int capturedParameterCount) {
        StringBuilder bounded = new StringBuilder(pattern.length());
        Map<ArgumentRequest, Integer> requests = new LinkedHashMap<>();
        boolean referencedParameterOmitted = false;
        boolean syntaxBounded = false;
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
            syntaxBounded |= width.changed();
            BoundedNumber precision = BoundedNumber.absent(cursor);
            boolean precisionPresent = cursor < pattern.length() && pattern.charAt(cursor) == '.';
            if (precisionPresent) {
                precision = boundedNumber(pattern, cursor + 1, maximumFieldWidth);
                cursor = precision.end();
                syntaxBounded |= precision.changed();
            }
            char datePrefix = cursor < pattern.length() && (pattern.charAt(cursor) == 't' || pattern.charAt(cursor) == 'T')
                    ? pattern.charAt(cursor++)
                    : '\0';
            char conversion = cursor < pattern.length() ? pattern.charAt(cursor++) : '\0';
            if (datePrefix != '\0' && precisionPresent && !precision.text().isEmpty()) {
                throw new IllegalFormatPrecisionException(Integer.parseInt(precision.text()));
            }

            if (conversion == '%' || conversion == 'n' || conversion == '\0') {
                if (explicit.present()) {
                    bounded.append(pattern, explicitStart, explicit.end());
                }
                if (reusePrevious) {
                    flags.append('<');
                }
                appendTail(bounded, flags, width, precisionPresent, precision, datePrefix, conversion);
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
            }

            int syntheticIndex = MISSING_ARGUMENT_INDEX;
            if (selected >= 0 && selected < capturedParameterCount) {
                ArgumentRequest request = request(selected, datePrefix, conversion);
                syntheticIndex = requests.computeIfAbsent(request, ignored -> requests.size()) + 1;
            } else if (selected >= 0 && selected < parameterCount) {
                referencedParameterOmitted = true;
            }
            bounded.append(syntheticIndex).append('$');
            char renderedConversion = datePrefix == 'T' ? 'S' : datePrefix == 't' ? 's' : conversion;
            appendTail(bounded, flags, width, precisionPresent, precision, '\0', renderedConversion);
        }
        return new Analysis(
                bounded.toString(),
                List.copyOf(requests.keySet()),
                referencedParameterOmitted,
                syntaxBounded,
                Locale.getDefault(Locale.Category.FORMAT),
                TrustedFormattingZone.defaultZone());
    }

    private static ArgumentRequest request(int argumentIndex, char datePrefix, char conversion) {
        if (datePrefix != '\0') {
            return new ArgumentRequest(argumentIndex, CaptureKind.TEMPORAL, conversion);
        }
        return switch (Character.toLowerCase(conversion)) {
            case 's' -> new ArgumentRequest(argumentIndex, CaptureKind.DISPLAY, '\0');
            case 'h' -> new ArgumentRequest(argumentIndex, CaptureKind.HASH, '\0');
            case 'b' -> new ArgumentRequest(argumentIndex, CaptureKind.BOOLEAN, '\0');
            default -> new ArgumentRequest(argumentIndex, CaptureKind.TYPED, '\0');
        };
    }

    private static void appendTail(
            StringBuilder target,
            CharSequence flags,
            BoundedNumber width,
            boolean precisionPresent,
            BoundedNumber precision,
            char datePrefix,
            char conversion) {
        target.append(flags).append(width.text());
        if (precisionPresent) {
            target.append('.').append(precision.text());
        }
        if (datePrefix != '\0') {
            target.append(datePrefix);
        }
        if (conversion != '\0') {
            target.append(conversion);
        }
    }

    record Analysis(
            String pattern,
            List<ArgumentRequest> arguments,
            boolean referencedParameterOmitted,
            boolean syntaxBounded,
            Locale locale,
            ZoneId zone) {
        PrintfArgumentCapture.CapturedArguments capture(Object[] parameters) {
            return PrintfArgumentCapture.capture(arguments, parameters, locale, zone);
        }
    }

    enum CaptureKind {
        DISPLAY,
        HASH,
        BOOLEAN,
        TEMPORAL,
        TYPED
    }

    record ArgumentRequest(int argumentIndex, CaptureKind kind, char temporalConversion) {
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
        long bounded = Math.min(value, maximum);
        return new BoundedNumber(Long.toString(bounded), end, value > maximum);
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

    private record BoundedNumber(String text, int end, boolean changed) {
        private static BoundedNumber absent(int cursor) {
            return new BoundedNumber("", cursor, false);
        }
    }
}
