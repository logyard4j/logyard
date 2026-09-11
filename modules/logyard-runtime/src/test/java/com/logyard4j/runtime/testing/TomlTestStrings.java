package com.logyard4j.runtime.testing;

import java.nio.file.Path;

/** TOML basic-string escaping for configuration text assembled by runtime tests. */
public final class TomlTestStrings {
    private TomlTestStrings() {
    }

    public static String escapeBasicString(Path value) {
        return escapeBasicString(value.toString());
    }

    public static String escapeBasicString(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\b' -> escaped.append("\\b");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\f' -> escaped.append("\\f");
                case '\r' -> escaped.append("\\r");
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                default -> {
                    if (character < 0x20 || character == 0x7f) {
                        escaped.append("\\u").append(String.format("%04X", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
