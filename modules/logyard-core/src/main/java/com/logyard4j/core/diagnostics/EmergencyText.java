package com.logyard4j.core.diagnostics;

import com.logyard4j.api.failure.FailureIsolation;

/** Bounded, terminal-safe text for diagnostics that bypass the normal logging pipeline. */
public final class EmergencyText {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private EmergencyText() {
    }

    public static String sanitize(String value, int maximum) {
        requireMaximum(maximum);
        if (value == null) {
            value = "null";
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), maximum));
        int index = 0;
        boolean truncated = false;
        while (index < value.length()) {
            char character = value.charAt(index);
            int consumed = 1;
            String fixedEscape = switch (character) {
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> null;
            };
            if (fixedEscape != null) {
                if (!fits(result, fixedEscape.length(), maximum)) {
                    truncated = true;
                    break;
                }
                result.append(fixedEscape);
            } else if (isControl(character)) {
                if (!fits(result, 6, maximum)) {
                    truncated = true;
                    break;
                }
                appendUnicodeEscape(result, character);
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                if (!fits(result, 2, maximum)) {
                    truncated = true;
                    break;
                }
                result.append(character).append(value.charAt(index + 1));
                consumed = 2;
            } else {
                if (!fits(result, 1, maximum)) {
                    truncated = true;
                    break;
                }
                result.append(character);
            }
            index += consumed;
        }
        if (index < value.length()) {
            truncated = true;
        }
        if (truncated) {
            appendEllipsis(result, maximum);
        }
        return result.toString();
    }

    public static String failureSummary(Throwable failure, int maximum) {
        requireMaximum(maximum);
        if (failure == null) {
            return sanitize(null, maximum);
        }
        String type = failure.getClass().getName();
        String message;
        try {
            message = failure.getMessage();
        } catch (Throwable accessorFailure) {
            FailureIsolation.prepareForRecovery(accessorFailure);
            message = "[message accessor failed: " + prefix(accessorFailure.getClass().getName(), maximum) + ']';
        }
        String summary = message == null || message.isBlank() ? type
                : prefix(type, maximum) + ": " + prefix(message, maximum);
        return sanitize(summary, maximum);
    }

    public static String threadComponent(String value, int maximum) {
        requireMaximum(maximum);
        if (value == null || value.isBlank()) {
            return prefix("unnamed", maximum);
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), maximum));
        for (int index = 0; index < value.length() && result.length() < maximum; index++) {
            char character = value.charAt(index);
            result.append(Character.isLetterOrDigit(character) || "_.-".indexOf(character) >= 0
                    ? character : '-');
        }
        if (result.length() == 0) {
            return prefix("unnamed", maximum);
        }
        return result.toString();
    }

    private static void requireMaximum(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be positive");
        }
    }

    private static String prefix(String value, int maximum) {
        // Escaping only expands text, so a raw prefix is enough to fill the final output allowance.
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static boolean fits(StringBuilder result, int addition, int maximum) {
        return result.length() + addition <= maximum;
    }

    private static boolean isControl(char character) {
        return character < 0x20
                || (character >= 0x7f && character <= 0x9f)
                || character == '\u061c'
                || character == '\u200e'
                || character == '\u200f'
                || character == '\u2028'
                || character == '\u2029'
                || (character >= '\u202a' && character <= '\u202e')
                || (character >= '\u2066' && character <= '\u2069');
    }

    private static void appendUnicodeEscape(StringBuilder result, char character) {
        result.append("\\u")
                .append(HEX[(character >>> 12) & 0xf])
                .append(HEX[(character >>> 8) & 0xf])
                .append(HEX[(character >>> 4) & 0xf])
                .append(HEX[character & 0xf]);
    }

    private static void appendEllipsis(StringBuilder result, int maximum) {
        while (result.length() >= maximum) {
            int last = result.length() - 1;
            if (last > 0 && Character.isLowSurrogate(result.charAt(last))
                    && Character.isHighSurrogate(result.charAt(last - 1))) {
                result.setLength(last - 1);
            } else {
                result.setLength(last);
            }
        }
        result.append('…');
    }
}
