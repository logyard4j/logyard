package com.zsumz.logyard.config.toml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Bounded TOML 1.0 parser for Logyard configuration values.
 *
 * <p>The parser accepts the TOML types used by Logyard and enforces TOML numeric,
 * key, duplicate-key, and duplicate-table rules before schema compilation.</p>
 */
public final class TomlParser {
    private final String sourceName;
    private final String input;
    private final Map<String, Object> root = new LinkedHashMap<>();
    private final Set<String> explicitTables = new HashSet<>();
    private int index;
    private int line = 1;
    private int column = 1;
    private Map<String, Object> current = root;

    private TomlParser(String sourceName, String input) {
        this.sourceName = sourceName;
        this.input = input;
    }

    public static TomlDocument parse(String sourceName, String input) {
        return new TomlParser(sourceName, input).parseDocument();
    }

    private TomlDocument parseDocument() {
        while (true) {
            skipBlankLinesAndComments();
            if (eof()) {
                return new TomlDocument(root);
            }
            if (peek() == '[') {
                parseHeader();
            } else {
                parseAssignment(current);
            }
            finishStatement();
        }
    }

    private void parseHeader() {
        expect('[');
        boolean array = consume('[');
        skipHorizontal();
        List<String> path = parseKeyPath(array ? ']' : ']');
        skipHorizontal();
        expect(']');
        if (array) {
            expect(']');
            current = createArrayTable(path);
        } else {
            current = createTable(path);
        }
    }

    private void parseAssignment(Map<String, Object> table) {
        List<String> path = parseKeyPath('=');
        skipHorizontal();
        expect('=');
        skipHorizontal();
        if (eof() || peek() == '\n' || peek() == '#') {
            fail("expected a value after '='");
        }
        Object value = parseValue();
        putPath(table, path, value);
    }

    private Object parseValue() {
        skipWhitespaceInValue();
        if (eof()) {
            fail("expected a value");
        }
        return switch (peek()) {
            case '"' -> parseBasicString();
            case '\'' -> parseLiteralString();
            case '[' -> parseArray();
            case '{' -> parseInlineTable();
            default -> parseBareValue();
        };
    }

    private String parseBasicString() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (!eof()) {
            char c = take();
            if (c == '"') {
                return result.toString();
            }
            if (c == '\n' || c == '\r') {
                fail("basic strings may not contain a raw newline");
            }
            if (c < 0x20 && c != '\t') {
                fail("basic strings may not contain control characters");
            }
            if (c != '\\') {
                result.append(c);
                continue;
            }
            if (eof()) {
                fail("unterminated escape sequence");
            }
            char escape = take();
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
                default -> fail("unknown string escape \\" + escape + "'");
            }
        }
        fail("unterminated basic string");
        return "";
    }

    private int parseHexCodePoint(int digits) {
        int result = 0;
        for (int count = 0; count < digits; count++) {
            if (eof()) {
                fail("incomplete Unicode escape");
            }
            int digit = Character.digit(take(), 16);
            if (digit < 0) {
                fail("Unicode escape contains a non-hex character");
            }
            result = (result << 4) | digit;
        }
        if (!Character.isValidCodePoint(result) || (result >= 0xD800 && result <= 0xDFFF)) {
            fail("Unicode escape is not a valid scalar value");
        }
        return result;
    }

    private String parseLiteralString() {
        expect('\'');
        StringBuilder result = new StringBuilder();
        while (!eof()) {
            char c = take();
            if (c == '\'') {
                return result.toString();
            }
            if (c == '\n' || c == '\r') {
                fail("literal strings may not contain a raw newline");
            }
            if (c < 0x20 && c != '\t') {
                fail("literal strings may not contain control characters");
            }
            result.append(c);
        }
        fail("unterminated literal string");
        return "";
    }

    private List<Object> parseArray() {
        expect('[');
        List<Object> result = new ArrayList<>();
        skipArraySpace();
        if (consume(']')) {
            return result;
        }
        while (true) {
            result.add(parseValue());
            skipArraySpace();
            if (consume(']')) {
                return result;
            }
            expect(',');
            skipArraySpace();
            if (consume(']')) {
                return result;
            }
        }
    }

    private Map<String, Object> parseInlineTable() {
        expect('{');
        Map<String, Object> result = new LinkedHashMap<>();
        skipHorizontal();
        if (consume('}')) {
            return result;
        }
        while (true) {
            List<String> path = parseKeyPath('=');
            skipHorizontal();
            expect('=');
            skipHorizontal();
            putPath(result, path, parseValue());
            skipHorizontal();
            if (consume('}')) {
                return result;
            }
            expect(',');
            skipHorizontal();
        }
    }

    private Object parseBareValue() {
        int start = index;
        while (!eof()) {
            char c = peek();
            if (Character.isWhitespace(c) || c == ',' || c == ']' || c == '}' || c == '#') {
                break;
            }
            take();
        }
        String token = input.substring(start, index);
        if (token.equals("true")) {
            return Boolean.TRUE;
        }
        if (token.equals("false")) {
            return Boolean.FALSE;
        }
        try {
            if (token.matches("0x[0-9A-Fa-f](?:_?[0-9A-Fa-f])*") ) {
                return Long.parseUnsignedLong(token.substring(2).replace("_", ""), 16);
            }
            if (token.matches("0o[0-7](?:_?[0-7])*") ) {
                return Long.parseUnsignedLong(token.substring(2).replace("_", ""), 8);
            }
            if (token.matches("0b[01](?:_?[01])*") ) {
                return Long.parseUnsignedLong(token.substring(2).replace("_", ""), 2);
            }
            if (token.matches("[+-]?(?:0|[1-9](?:_?[0-9])*)")) {
                return Long.valueOf(token.replace("_", ""));
            }
            if (token.matches("[+-]?(?:"
                    + "(?:0|[1-9](?:_?[0-9])*)\\.[0-9](?:_?[0-9])*"
                    + "|(?:0|[1-9](?:_?[0-9])*)(?:[eE][+-]?[0-9](?:_?[0-9])*)"
                    + "|(?:0|[1-9](?:_?[0-9])*)\\.[0-9](?:_?[0-9])*(?:[eE][+-]?[0-9](?:_?[0-9])*)"
                    + ")")) {
                return Double.valueOf(token.replace("_", ""));
            }
            if (token.matches("[+-]?(?:inf|nan)")) {
                if (token.endsWith("inf")) {
                    return token.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
                }
                return Double.NaN;
            }
        } catch (NumberFormatException exception) {
            fail("numeric value is outside the supported range: '" + token + "'");
        }
        if (looksLikeNumber(token)) {
            if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
                fail("invalid numeric syntax: '" + token + "'");
            }
            fail("invalid integer syntax: '" + token + "'");
        }
        fail("unsupported bare value '" + token + "'; strings must be quoted");
        return null;
    }

    private static boolean looksLikeNumber(String token) {
        if (token.isEmpty()) {
            return false;
        }
        int first = token.charAt(0) == '+' || token.charAt(0) == '-' ? 1 : 0;
        return first < token.length() && Character.isDigit(token.charAt(first));
    }

    private List<String> parseKeyPath(char terminator) {
        List<String> result = new ArrayList<>();
        while (true) {
            skipHorizontal();
            if (eof() || peek() == terminator) {
                fail("expected a key");
            }
            String segment;
            if (peek() == '"') {
                segment = parseBasicString();
            } else if (peek() == '\'') {
                segment = parseLiteralString();
            } else {
                int start = index;
                while (!eof()) {
                    char c = peek();
                    if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                        take();
                    } else {
                        break;
                    }
                }
                if (start == index) {
                    fail("expected a bare or quoted key");
                }
                segment = input.substring(start, index);
            }
            result.add(segment);
            skipHorizontal();
            if (!consume('.')) {
                return result;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createTable(List<String> path) {
        String tableName = String.join(".", path);
        if (!explicitTables.add(tableName)) {
            fail("table '[" + tableName + "]' is already defined");
        }
        Map<String, Object> cursor = root;
        for (int i = 0; i < path.size(); i++) {
            String segment = path.get(i);
            Object existing = cursor.get(segment);
            if (existing instanceof List<?> list) {
                if (list.isEmpty() || !(list.get(list.size() - 1) instanceof Map<?, ?>)) {
                    fail("table path '" + String.join(".", path) + "' has no current array element");
                }
                cursor = (Map<String, Object>) list.get(list.size() - 1);
                continue;
            }
            if (existing == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                cursor.put(segment, created);
                cursor = created;
            } else if (existing instanceof Map<?, ?> map) {
                cursor = (Map<String, Object>) map;
            } else {
                fail("table path collides with an existing value at '" + segment + "'");
            }
        }
        return cursor;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createArrayTable(List<String> path) {
        if (path.isEmpty()) {
            fail("array table path must not be empty");
        }
        Map<String, Object> parent = root;
        for (int i = 0; i < path.size() - 1; i++) {
            String segment = path.get(i);
            Object existing = parent.get(segment);
            if (existing instanceof List<?> list) {
                if (list.isEmpty() || !(list.get(list.size() - 1) instanceof Map<?, ?>)) {
                    fail("array table parent has no current element");
                }
                parent = (Map<String, Object>) list.get(list.size() - 1);
            } else if (existing instanceof Map<?, ?> map) {
                parent = (Map<String, Object>) map;
            } else if (existing == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                parent.put(segment, created);
                parent = created;
            } else {
                fail("array table path collides with an existing value at '" + segment + "'");
            }
        }
        String leaf = path.get(path.size() - 1);
        Object existing = parent.get(leaf);
        List<Object> list;
        if (existing == null) {
            list = new ArrayList<>();
            parent.put(leaf, list);
        } else if (existing instanceof List<?> currentList) {
            list = (List<Object>) currentList;
        } else {
            fail("array table path collides with an existing value at '" + leaf + "'");
            return parent;
        }
        Map<String, Object> element = new LinkedHashMap<>();
        list.add(element);
        return element;
    }

    @SuppressWarnings("unchecked")
    private void putPath(Map<String, Object> table, List<String> path, Object value) {
        Map<String, Object> cursor = table;
        for (int i = 0; i < path.size() - 1; i++) {
            String segment = path.get(i);
            Object existing = cursor.get(segment);
            if (existing == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                cursor.put(segment, created);
                cursor = created;
            } else if (existing instanceof Map<?, ?> map) {
                cursor = (Map<String, Object>) map;
            } else {
                fail("dotted key collides with existing value at '" + segment + "'");
            }
        }
        String leaf = path.get(path.size() - 1);
        if (cursor.putIfAbsent(leaf, value) != null) {
            fail("duplicate key '" + String.join(".", path) + "'");
        }
    }

    private void finishStatement() {
        skipHorizontal();
        if (!eof() && peek() == '#') {
            while (!eof() && peek() != '\n') {
                take();
            }
        }
        if (!eof() && peek() != '\n') {
            fail("unexpected content after statement");
        }
        if (!eof()) {
            take();
        }
    }

    private void skipBlankLinesAndComments() {
        while (true) {
            skipHorizontal();
            if (!eof() && peek() == '#') {
                while (!eof() && peek() != '\n') {
                    take();
                }
            }
            if (!eof() && peek() == '\n') {
                take();
                continue;
            }
            return;
        }
    }

    private void skipHorizontal() {
        while (!eof() && (peek() == ' ' || peek() == '\t' || peek() == '\r')) {
            take();
        }
    }

    private void skipWhitespaceInValue() {
        skipHorizontal();
    }

    private void skipArraySpace() {
        while (true) {
            while (!eof() && Character.isWhitespace(peek())) {
                take();
            }
            if (!eof() && peek() == '#') {
                while (!eof() && peek() != '\n') {
                    take();
                }
                continue;
            }
            return;
        }
    }

    private boolean consume(char expected) {
        if (!eof() && peek() == expected) {
            take();
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (eof() || peek() != expected) {
            fail("expected '" + expected + "'");
        }
        take();
    }

    private char peek() { return input.charAt(index); }

    private char take() {
        char result = input.charAt(index++);
        if (result == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return result;
    }

    private boolean eof() { return index >= input.length(); }

    private void fail(String message) {
        throw new TomlParseException(sourceName, line, column, message);
    }
}
