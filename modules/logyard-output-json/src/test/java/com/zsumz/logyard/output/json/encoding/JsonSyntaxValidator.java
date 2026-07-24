package com.zsumz.logyard.output.json.encoding;

/** Minimal recursive-descent validator used to prove capped output remains one complete JSON value. */
final class JsonSyntaxValidator {
    private final String source;
    private int cursor;

    private JsonSyntaxValidator(String source) {
        this.source = source;
    }

    static void requireValid(String source) {
        JsonSyntaxValidator validator = new JsonSyntaxValidator(source);
        validator.value();
        validator.whitespace();
        validator.require(validator.cursor == source.length(), "trailing content");
    }

    private void value() {
        whitespace();
        require(cursor < source.length(), "missing value");
        switch (source.charAt(cursor)) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true");
            case 'f' -> literal("false");
            case 'n' -> literal("null");
            default -> number();
        }
    }

    private void object() {
        cursor++;
        whitespace();
        if (take('}')) {
            return;
        }
        while (true) {
            whitespace();
            string();
            whitespace();
            require(take(':'), "missing object colon");
            value();
            whitespace();
            if (take('}')) {
                return;
            }
            require(take(','), "missing object comma");
        }
    }

    private void array() {
        cursor++;
        whitespace();
        if (take(']')) {
            return;
        }
        while (true) {
            value();
            whitespace();
            if (take(']')) {
                return;
            }
            require(take(','), "missing array comma");
        }
    }

    private void string() {
        require(take('"'), "missing string");
        while (cursor < source.length()) {
            char current = source.charAt(cursor++);
            if (current == '"') {
                return;
            }
            require(current >= 0x20, "raw control character");
            if (current == '\\') {
                escape();
            }
        }
        throw invalid("unterminated string");
    }

    private void escape() {
        require(cursor < source.length(), "unterminated escape");
        char escaped = source.charAt(cursor++);
        if (escaped == 'u') {
            for (int digit = 0; digit < 4; digit++) {
                require(cursor < source.length() && Character.digit(source.charAt(cursor++), 16) >= 0, "invalid unicode escape");
            }
            return;
        }
        require("\"\\/bfnrt".indexOf(escaped) >= 0, "invalid escape");
    }

    private void number() {
        int start = cursor;
        take('-');
        require(cursor < source.length(), "missing number");
        if (take('0')) {
            require(cursor == source.length() || !Character.isDigit(source.charAt(cursor)), "leading zero");
        } else {
            digits();
        }
        if (take('.')) {
            digits();
        }
        if (take('e') || take('E')) {
            if (!take('+')) {
                take('-');
            }
            digits();
        }
        require(cursor > start, "missing number");
    }

    private void digits() {
        int start = cursor;
        while (cursor < source.length() && Character.isDigit(source.charAt(cursor))) {
            cursor++;
        }
        require(cursor > start, "missing digits");
    }

    private void literal(String expected) {
        require(source.startsWith(expected, cursor), "invalid literal");
        cursor += expected.length();
    }

    private void whitespace() {
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
    }

    private boolean take(char expected) {
        if (cursor < source.length() && source.charAt(cursor) == expected) {
            cursor++;
            return true;
        }
        return false;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw invalid(message);
        }
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message + " at character " + cursor);
    }
}
