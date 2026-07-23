package com.zsumz.logyard.config.toml;

@SuppressWarnings("serial")
public final class TomlParseException extends IllegalArgumentException {
    public TomlParseException(String source, int line, int column, String message) {
        super(source + ":" + line + ":" + column + ": " + message);
    }
}
