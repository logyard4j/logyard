package com.logyard4j.config.loading.overlay;

import com.logyard4j.config.ConfigurationException;
import com.logyard4j.config.toml.TomlFragment;
import com.logyard4j.config.toml.TomlParseException;

import java.util.Objects;

/**
 * One key-level configuration override captured from the process.
 *
 * <p>The key uses TOML key-path grammar, so quoted segments address dotted names
 * exactly as the configuration file would. The value is TOML syntax; a value that is
 * not valid TOML and does not start with a TOML structure or quote is taken as a
 * plain string, so {@code level=debug} and {@code flush=2s} work without shell-hostile
 * quoting while a malformed array or table stays an error instead of silently
 * becoming text.</p>
 */
public record OverrideEntry(String key, String value, String origin) {
    /** Maximum characters of one override key. */
    public static final int MAX_KEY_CHARS = 512;
    /** Maximum characters of one override value. */
    public static final int MAX_VALUE_CHARS = 4_096;

    public OverrideEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(origin, "origin");
        if (key.isBlank()) {
            throw new ConfigurationException(origin + ": override key must not be blank");
        }
        if (key.length() > MAX_KEY_CHARS) {
            throw new ConfigurationException(origin + ": override key exceeds " + MAX_KEY_CHARS + " characters");
        }
        if (value.length() > MAX_VALUE_CHARS) {
            throw new ConfigurationException(origin + ": override value exceeds " + MAX_VALUE_CHARS + " characters");
        }
    }

    /** Parses this override into a key path and TOML value. */
    public TomlFragment parse() {
        String trimmedValue = value.trim();
        try {
            return TomlFragment.parseAssignment(origin, key + " = " + trimmedValue);
        } catch (TomlParseException direct) {
            if (startsStructuredValue(trimmedValue)) {
                throw new ConfigurationException(origin + ": invalid override: " + direct.getMessage(), direct);
            }
            try {
                return TomlFragment.parseAssignment(origin, key + " = " + quoted(trimmedValue));
            } catch (TomlParseException invalidKey) {
                throw new ConfigurationException(origin + ": invalid override: " + direct.getMessage(), direct);
            }
        }
    }

    private static boolean startsStructuredValue(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return first == '[' || first == '{' || first == '"' || first == '\'';
    }

    private static String quoted(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (Character.isISOControl(character)) {
                        result.append(String.format("\\u%04X", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }
}
