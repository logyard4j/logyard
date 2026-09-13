package com.logyard4j.logyard.config.toml;

import java.util.List;

/**
 * Parses one {@code key.path = value} assignment in TOML syntax.
 *
 * <p>Override entries reuse the exact TOML grammar of the configuration file: dotted,
 * bare, or quoted key segments on the left, and any TOML value on the right. The
 * parsed path keeps its raw segments so callers can address the configuration
 * document precisely.</p>
 */
public record TomlFragment(List<String> path, Object value) {
    public TomlFragment {
        path = List.copyOf(path);
    }

    /**
     * Parses one complete assignment; the complete input must be consumed.
     *
     * @throws TomlParseException when the input is not exactly one TOML assignment
     */
    public static TomlFragment parseAssignment(String sourceName, String input) {
        TomlCursor cursor = new TomlCursor(sourceName, input);
        TomlValueParser values = new TomlValueParser(cursor, new TomlTableBuilder(cursor));
        cursor.skipHorizontal();
        List<String> path = values.parseKeyPath('=');
        cursor.skipHorizontal();
        cursor.expect('=');
        cursor.skipHorizontal();
        if (cursor.eof()) {
            cursor.fail("expected a value after '='");
        }
        Object value = values.parseValue();
        cursor.skipHorizontal();
        if (!cursor.eof()) {
            cursor.fail("unexpected content after the assignment value");
        }
        return new TomlFragment(path, value);
    }
}
