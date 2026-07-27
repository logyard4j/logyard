package com.zsumz.logyard.api.event;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Analyzes printf conversions into bounded syntax and conversion-specific captured arguments. */
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

            PrintfConversionParser.Conversion conversion = PrintfConversionParser.parse(pattern, cursor, maximumFieldWidth);
            cursor = conversion.end();
            syntaxBounded |= conversion.width().changed() || conversion.precision().changed();
            if (conversion.passThrough()) {
                if (conversion.explicit().present()) {
                    bounded.append(pattern, conversion.explicitStart(), conversion.explicit().end());
                }
                appendTail(
                        bounded,
                        conversion.flags() + (conversion.reusePrevious() ? '<' : ""),
                        conversion.width(),
                        conversion.precisionPresent(),
                        conversion.precision(),
                        conversion.datePrefix(),
                        conversion.conversion());
                continue;
            }

            int selected = conversion.reusePrevious()
                    ? previousArgument
                    : conversion.explicit().present() ? conversion.explicit().zeroBased() : ordinaryArgument++;
            if (selected >= 0) {
                previousArgument = selected;
            }

            int syntheticIndex = MISSING_ARGUMENT_INDEX;
            if (selected >= 0 && selected < capturedParameterCount) {
                ArgumentRequest request = request(selected, conversion.datePrefix(), conversion.conversion());
                syntheticIndex = requests.computeIfAbsent(request, ignored -> requests.size()) + 1;
            } else if (selected >= 0 && selected < parameterCount) {
                referencedParameterOmitted = true;
            }
            bounded.append(syntheticIndex).append('$');
            char renderedConversion = conversion.datePrefix() == 'T' ? 'S' : conversion.datePrefix() == 't' ? 's' : conversion.conversion();
            appendTail(
                    bounded,
                    conversion.flags(),
                    conversion.width(),
                    conversion.precisionPresent(),
                    conversion.precision(),
                    '\0',
                    renderedConversion);
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
            PrintfConversionParser.BoundedNumber width,
            boolean precisionPresent,
            PrintfConversionParser.BoundedNumber precision,
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
}
