package com.zsumz.logyard.config.toml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recursive-descent parser for TOML values and dotted key paths. */
final class TomlValueParser {
    private final TomlCursor cursor;
    private final TomlTableBuilder tables;

    TomlValueParser(TomlCursor cursor, TomlTableBuilder tables) {
        this.cursor = cursor;
        this.tables = tables;
    }

    Object parseValue() {
        cursor.skipHorizontal();
        if (cursor.eof()) {
            cursor.fail("expected a value");
        }
        return switch (cursor.peek()) {
            case '"' -> parseBasicString();
            case '\'' -> parseLiteralString();
            case '[' -> parseArray();
            case '{' -> parseInlineTable();
            default -> parseBareValue();
        };
    }

    List<String> parseKeyPath(char terminator) {
        List<String> result = new ArrayList<>();
        while (true) {
            cursor.skipHorizontal();
            if (cursor.eof() || cursor.peek() == terminator) {
                cursor.fail("expected a key");
            }
            result.add(parseKeySegment());
            cursor.skipHorizontal();
            if (!cursor.consume('.')) {
                return result;
            }
        }
    }

    private String parseKeySegment() {
        if (cursor.peek() == '"') {
            return parseBasicString();
        }
        if (cursor.peek() == '\'') {
            return parseLiteralString();
        }
        int start = cursor.mark();
        while (!cursor.eof()) {
            char character = cursor.peek();
            if (Character.isLetterOrDigit(character) || character == '_' || character == '-') {
                cursor.take();
            } else {
                break;
            }
        }
        if (start == cursor.mark()) {
            cursor.fail("expected a bare or quoted key");
        }
        return cursor.textFrom(start);
    }

    private String parseBasicString() {
        cursor.expect('"');
        StringBuilder result = new StringBuilder();
        while (!cursor.eof()) {
            char character = cursor.take();
            if (character == '"') {
                return result.toString();
            }
            validateStringCharacter(character, "basic");
            if (character != '\\') {
                result.append(character);
                continue;
            }
            appendEscape(result);
        }
        cursor.fail("unterminated basic string");
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

    private String parseLiteralString() {
        cursor.expect('\'');
        StringBuilder result = new StringBuilder();
        while (!cursor.eof()) {
            char character = cursor.take();
            if (character == '\'') {
                return result.toString();
            }
            validateStringCharacter(character, "literal");
            result.append(character);
        }
        cursor.fail("unterminated literal string");
        throw new AssertionError("unreachable");
    }

    private void validateStringCharacter(char character, String kind) {
        if (character == '\n' || character == '\r') {
            cursor.fail(kind + " strings may not contain a raw newline");
        }
        if (character < 0x20 && character != '\t') {
            cursor.fail(kind + " strings may not contain control characters");
        }
    }

    private List<Object> parseArray() {
        cursor.expect('[');
        List<Object> result = new ArrayList<>();
        cursor.skipArraySpace();
        if (cursor.consume(']')) {
            return result;
        }
        while (true) {
            result.add(parseValue());
            cursor.skipArraySpace();
            if (cursor.consume(']')) {
                return result;
            }
            cursor.expect(',');
            cursor.skipArraySpace();
            if (cursor.consume(']')) {
                return result;
            }
        }
    }

    private Map<String, Object> parseInlineTable() {
        cursor.expect('{');
        Map<String, Object> result = new LinkedHashMap<>();
        cursor.skipHorizontal();
        if (cursor.consume('}')) {
            return result;
        }
        while (true) {
            List<String> path = parseKeyPath('=');
            cursor.skipHorizontal();
            cursor.expect('=');
            cursor.skipHorizontal();
            tables.putPath(result, path, parseValue());
            cursor.skipHorizontal();
            if (cursor.consume('}')) {
                return result;
            }
            cursor.expect(',');
            cursor.skipHorizontal();
        }
    }

    private Object parseBareValue() {
        int start = cursor.mark();
        while (!cursor.eof()) {
            char character = cursor.peek();
            if (Character.isWhitespace(character) || character == ',' || character == ']' || character == '}' || character == '#') {
                break;
            }
            cursor.take();
        }
        return TomlBareValueParser.parse(cursor.textFrom(start), cursor);
    }
}
