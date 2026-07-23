package com.zsumz.logyard.core.diagnostics;

/** Bounded, terminal-safe text for diagnostics that bypass the normal logging pipeline. */
public final class EmergencyText {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private EmergencyText() {
    }

    public static String sanitize(String value, int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be positive");
        }
        if (value == null) {
            return "null";
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
        if (failure == null) {
            return "null";
        }
        String type = failure.getClass().getName();
        String message;
        try {
            message = failure.getMessage();
        } catch (RuntimeException accessorFailure) {
            message = "[message accessor failed: " + accessorFailure.getClass().getName() + ']';
        }
        String summary = message == null || message.isBlank() ? type : type + ": " + message;
        return sanitize(summary, maximum);
    }

    public static String threadComponent(String value, int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be positive");
        }
        if (value == null || value.isBlank()) {
            return "unnamed";
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), maximum));
        for (int index = 0; index < value.length() && result.length() < maximum; index++) {
            char character = value.charAt(index);
            result.append(Character.isLetterOrDigit(character) || "_.-".indexOf(character) >= 0
                    ? character : '-');
        }
        if (result.length() == 0) {
            return "unnamed";
        }
        return result.toString();
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
