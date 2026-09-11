package com.logyard4j.config.toml;

/** Parses TOML basic and literal strings, including bounded escape and Unicode validation. */
final class TomlStringParser {
    private final TomlCursor cursor;

    TomlStringParser(TomlCursor cursor) {
        this.cursor = cursor;
    }

    String parseBasic() {
        cursor.expect('"');
        StringBuilder result = new StringBuilder();
        while (!cursor.eof()) {
            char character = cursor.take();
            if (character == '"') {
                return result.toString();
            }
            validateCharacter(character, "basic");
            if (character != '\\') {
                result.append(character);
            } else {
                appendEscape(result);
            }
        }
        cursor.fail("unterminated basic string");
        throw new AssertionError("unreachable");
    }

    String parseLiteral() {
        cursor.expect('\'');
        StringBuilder result = new StringBuilder();
        while (!cursor.eof()) {
            char character = cursor.take();
            if (character == '\'') {
                return result.toString();
            }
            validateCharacter(character, "literal");
            result.append(character);
        }
        cursor.fail("unterminated literal string");
        throw new AssertionError("unreachable");
    }

    private void appendEscape(StringBuilder result) {
        if (cursor.eof()) {
            cursor.fail("unterminated escape sequence");
        }
        char escape = cursor.take();
        switch (escape) {
            case 'b' -> result.append('\b');
            case 't' -> result.append('\t');
            case 'n' -> result.append('\n');
            case 'f' -> result.append('\f');
            case 'r' -> result.append('\r');
            case '"' -> result.append('"');
            case '\\' -> result.append('\\');
            case 'u' -> result.appendCodePoint(parseHexCodePoint(4));
            case 'U' -> result.appendCodePoint(parseHexCodePoint(8));
            default -> cursor.fail("unknown string escape \\" + escape + "'");
        }
    }

    private int parseHexCodePoint(int digits) {
        int result = 0;
        for (int count = 0; count < digits; count++) {
            if (cursor.eof()) {
                cursor.fail("incomplete Unicode escape");
            }
            int digit = Character.digit(cursor.take(), 16);
            if (digit < 0) {
                cursor.fail("Unicode escape contains a non-hex character");
            }
            result = (result << 4) | digit;
        }
        if (!Character.isValidCodePoint(result) || (result >= 0xD800 && result <= 0xDFFF)) {
            cursor.fail("Unicode escape is not a valid scalar value");
        }
        return result;
    }

    private void validateCharacter(char character, String kind) {
        if (character == '\n' || character == '\r') {
            cursor.fail(kind + " strings may not contain a raw newline");
        }
        if (character < 0x20 && character != '\t') {
            cursor.fail(kind + " strings may not contain control characters");
        }
    }
}
