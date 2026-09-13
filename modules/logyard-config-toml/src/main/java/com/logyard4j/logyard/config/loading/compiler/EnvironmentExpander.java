package com.logyard4j.logyard.config.loading.compiler;

import com.logyard4j.logyard.config.ConfigurationException;

/** Expands bounded shell-style environment references in configuration strings. */
final class EnvironmentExpander {
    private EnvironmentExpander() {
    }

    static String expand(String value, ConfigSource context, String path) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), ConfigurationCompiler.MAX_EXPANDED_STRING_CHARS));
        int cursor = 0;
        while (cursor < value.length()) {
            if (value.startsWith("$${", cursor)) {
                appendBounded(result, "${", context, path);
                cursor += 3;
                continue;
            }
            int opening = value.indexOf("${", cursor);
            if (opening < 0) {
                appendBounded(result, value.substring(cursor), context, path);
                break;
            }
            appendBounded(result, value.substring(cursor, opening), context, path);
            int closing = value.indexOf('}', opening + 2);
            if (closing < 0) {
                throw context.failure(path, "unterminated environment expression");
            }

            String expression = value.substring(opening + 2, closing);
            int defaultAt = expression.indexOf(":-");
            String name = defaultAt < 0 ? expression : expression.substring(0, defaultAt);
            String fallback = defaultAt < 0 ? null : expression.substring(defaultAt + 2);
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw context.failure(path, "invalid environment name '" + name + "'");
            }

            String replacement = context.environment().get(name);
            if (replacement == null) {
                if (fallback == null) {
                    throw context.failure(path, "environment variable " + name + " is not set and has no default");
                }
                replacement = fallback;
            }
            appendBounded(result, replacement, context, path);
            cursor = closing + 1;
        }
        return result.toString();
    }

    private static void appendBounded(StringBuilder result, String value, ConfigSource context, String path) {
        if (result.length() + value.length() > ConfigurationCompiler.MAX_EXPANDED_STRING_CHARS) {
            throw overflow(context, path);
        }
        result.append(value);
    }

    private static ConfigurationException overflow(ConfigSource context, String path) {
        return context.failure(
                path, "expanded string exceeds " + ConfigurationCompiler.MAX_EXPANDED_STRING_CHARS + " characters");
    }
}
