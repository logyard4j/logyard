package com.zsumz.logyard.output.console.rendering;

import com.zsumz.logyard.api.event.CaptureLimits;

final class ConsoleText {
    private ConsoleText() {
    }

    static String safe(Object value) {
        return safe(value, CaptureLimits.MAX_TEXT_CHARS);
    }

    static String safe(Object value, int maximumCharacters) {
        return new ConsoleValueRenderer(maximumCharacters).render(value);
    }

    static String sanitize(String value) {
        return sanitize(value, CaptureLimits.MAX_TEXT_CHARS);
    }

    static String sanitize(String value, int maximumCharacters) {
        ConsoleTextBuffer output = new ConsoleTextBuffer(maximumCharacters);
        output.appendSanitized(value);
        return output.finish();
    }

    static String truncate(String value, int maximumCharacters) {
        ConsoleTextBuffer output = new ConsoleTextBuffer(maximumCharacters);
        output.append(value);
        return output.finish();
    }

    static boolean isUnsafeControl(char character) {
        return Character.isISOControl(character)
                || character == '\u061c'
                || character == '\u200e'
                || character == '\u200f'
                || (character >= '\u202a' && character <= '\u202e')
                || (character >= '\u2066' && character <= '\u2069');
    }
}
