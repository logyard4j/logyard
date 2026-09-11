package com.logyard4j.config.toml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recursive-descent parser for TOML values and dotted key paths. */
final class TomlValueParser {
    private final TomlCursor cursor;
    private final TomlTableBuilder tables;
    private final TomlStringParser strings;

    TomlValueParser(TomlCursor cursor, TomlTableBuilder tables) {
        this.cursor = cursor;
        this.tables = tables;
        strings = new TomlStringParser(cursor);
    }

    Object parseValue() {
        cursor.skipHorizontal();
        if (cursor.eof()) {
            cursor.fail("expected a value");
        }
        return switch (cursor.peek()) {
            case '"' -> strings.parseBasic();
            case '\'' -> strings.parseLiteral();
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
            return strings.parseBasic();
        }
        if (cursor.peek() == '\'') {
            return strings.parseLiteral();
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
