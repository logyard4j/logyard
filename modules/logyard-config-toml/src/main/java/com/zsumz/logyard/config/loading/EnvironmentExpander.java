package com.zsumz.logyard.config.loading;

import com.zsumz.logyard.config.ConfigurationException;
import java.util.Map;

/** Expands bounded shell-style environment references in configuration strings. */
final class EnvironmentExpander {
    private EnvironmentExpander() {
    }

    static String expand(String value, Map<String, String> environment, String source, String path) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), LogyardConfigLoader.MAX_EXPANDED_STRING_CHARS));
        int cursor = 0;
        while (cursor < value.length()) {
            if (value.startsWith("$${", cursor)) {
                appendBounded(result, "${", source, path);
                cursor += 3;
                continue;
            }
            int opening = value.indexOf("${", cursor);
            if (opening < 0) {
                appendBounded(result, value.substring(cursor), source, path);
                break;
            }
            appendBounded(result, value.substring(cursor, opening), source, path);
            int closing = value.indexOf('}', opening + 2);
            if (closing < 0) {
                throw failure(source, path, "unterminated environment expression");
            }

            String expression = value.substring(opening + 2, closing);
            int defaultAt = expression.indexOf(":-");
            String name = defaultAt < 0 ? expression : expression.substring(0, defaultAt);
            String fallback = defaultAt < 0 ? null : expression.substring(defaultAt + 2);
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw failure(source, path, "invalid environment name '" + name + "'");
            }

            String replacement = environment.get(name);
            if (replacement == null) {
                if (fallback == null) {
                    throw failure(source, path, "environment variable " + name + " is not set and has no default");
                }
                replacement = fallback;
            }
            appendBounded(result, replacement, source, path);
            cursor = closing + 1;
        }
        return result.toString();
    }

    private static void appendBounded(StringBuilder result, String value, String source, String path) {
        if (result.length() + value.length() > LogyardConfigLoader.MAX_EXPANDED_STRING_CHARS) {
            throw failure(source, path, "expanded string exceeds " + LogyardConfigLoader.MAX_EXPANDED_STRING_CHARS + " characters");
        }
        result.append(value);
    }

    private static ConfigurationException failure(String source, String path, String message) {
        return new ConfigurationException(source + ": " + path + ": " + message);
    }
}
