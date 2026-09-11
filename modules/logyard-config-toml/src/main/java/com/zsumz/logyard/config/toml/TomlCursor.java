package com.zsumz.logyard.config.toml;

/** Position-aware source cursor and whitespace policy for the TOML recursive-descent parser. */
final class TomlCursor {
    private final String sourceName;
    private final String input;
    private int index;
    private int line = 1;
    private int column = 1;

    TomlCursor(String sourceName, String input) {
        this.sourceName = sourceName;
        this.input = input;
    }

    boolean eof() {
        return index >= input.length();
    }

    int line() {
        return line;
    }

    char peek() {
        return input.charAt(index);
    }

    char take() {
        char result = input.charAt(index++);
        if (result == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return result;
    }

    boolean consume(char expected) {
        if (!eof() && peek() == expected) {
            take();
            return true;
        }
        return false;
    }

    void expect(char expected) {
        if (eof() || peek() != expected) {
            fail("expected '" + expected + "'");
        }
        take();
    }

    int mark() {
        return index;
    }

    String textFrom(int start) {
        return input.substring(start, index);
    }

    void skipHorizontal() {
        while (!eof() && (peek() == ' ' || peek() == '\t' || peek() == '\r')) {
            take();
        }
    }

    void skipArraySpace() {
        while (true) {
            while (!eof() && Character.isWhitespace(peek())) {
                take();
            }
            if (!eof() && peek() == '#') {
                skipComment();
                continue;
            }
            return;
        }
    }

    void skipBlankLinesAndComments() {
        while (true) {
            skipHorizontal();
            if (!eof() && peek() == '#') {
                skipComment();
            }
            if (!eof() && peek() == '\n') {
                take();
                continue;
            }
            return;
        }
    }

    void finishStatement() {
        skipHorizontal();
        if (!eof() && peek() == '#') {
            skipComment();
        }
        if (!eof() && peek() != '\n') {
            fail("unexpected content after statement");
        }
        if (!eof()) {
            take();
        }
    }

    void fail(String message) {
        throw new TomlParseException(sourceName, line, column, message);
    }

    private void skipComment() {
        while (!eof() && peek() != '\n') {
            take();
        }
    }
}
