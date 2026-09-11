package com.zsumz.logyard.config.toml;

import java.util.List;
import java.util.Map;

/**
 * Bounded TOML 1.0 parser for Logyard configuration values.
 *
 * <p>The parser accepts the TOML types used by Logyard and enforces TOML numeric,
 * key, duplicate-key, and duplicate-table rules before schema compilation.</p>
 */
public final class TomlParser {
    private final TomlCursor cursor;
    private final TomlTableBuilder tables;
    private final TomlValueParser values;

    private TomlParser(String sourceName, String input) {
        cursor = new TomlCursor(sourceName, input);
        tables = new TomlTableBuilder(cursor);
        values = new TomlValueParser(cursor, tables);
    }

    public static TomlDocument parse(String sourceName, String input) {
        return new TomlParser(sourceName, input).parseDocument();
    }

    private TomlDocument parseDocument() {
        while (true) {
            cursor.skipBlankLinesAndComments();
            if (cursor.eof()) {
                return tables.document();
            }
            if (cursor.peek() == '[') {
                parseHeader();
            } else {
                parseAssignment(tables.current());
            }
            cursor.finishStatement();
        }
    }

    private void parseHeader() {
        int line = cursor.line();
        cursor.expect('[');
        boolean array = cursor.consume('[');
        cursor.skipHorizontal();
        List<String> path = values.parseKeyPath(']');
        cursor.skipHorizontal();
        cursor.expect(']');
        if (array) {
            cursor.expect(']');
        }
        tables.select(path, array, line);
    }

    private void parseAssignment(Map<String, Object> table) {
        int line = cursor.line();
        List<String> path = values.parseKeyPath('=');
        cursor.skipHorizontal();
        cursor.expect('=');
        cursor.skipHorizontal();
        if (cursor.eof() || cursor.peek() == '\n' || cursor.peek() == '#') {
            cursor.fail("expected a value after '='");
        }
        Object value = values.parseValue();
        tables.putPath(table, path, value);
        tables.recordAssignment(path, value, line);
    }
}
