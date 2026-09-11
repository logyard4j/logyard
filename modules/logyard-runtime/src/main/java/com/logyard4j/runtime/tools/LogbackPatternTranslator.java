package com.logyard4j.runtime.tools;

import java.util.ArrayList;
import java.util.List;

/** Translates the small supported Logback pattern subset without adding event fields. */
final class LogbackPatternTranslator {
    private LogbackPatternTranslator() {
    }

    static PatternTranslation translate(String pattern) {
        StringBuilder template = new StringBuilder();
        List<String> losses = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        if (!pattern.endsWith("%n")) losses.add("a final record newline was added by the console output");
        int index = 0;
        while (index < pattern.length()) {
            char character = pattern.charAt(index);
            if (character != '%') {
                if (character == '(' || character == ')' || character == '\\'
                        || character == '\'' || character == '"') {
                    unsupported.add("Logback grouping, quoting, and escapes require manual template conversion");
                }
                template.append(character);
                if (character == '{' || character == '}') template.append(character);
                index++;
                continue;
            }
            int tokenStart = index++;
            int modifierStart = index;
            while (index < pattern.length()
                    && (pattern.charAt(index) == '-' || pattern.charAt(index) == '.'
                            || Character.isDigit(pattern.charAt(index)))) index++;
            int start = index;
            while (index < pattern.length() && Character.isLetter(pattern.charAt(index))) index++;
            String conversion = pattern.substring(start, index);
            boolean options = index < pattern.length() && pattern.charAt(index) == '{';
            if (options) {
                int closing = pattern.indexOf('}', index);
                index = closing < 0 ? pattern.length() : closing + 1;
            }
            String token = pattern.substring(tokenStart, index);
            if (start != modifierStart || options) losses.add(token + ": width or options were not preserved");
            String placeholder = placeholder(conversion);
            if (conversion.equals("X") || conversion.equals("mdc") || conversion.equals("kvp")) {
                unsupported.add(token + ": context/key-value selection is unsupported; no fields or MDC capture were added");
            } else if (placeholder == null) {
                unsupported.add(token + " has no template equivalent and was dropped");
            } else {
                template.append(placeholder);
                if (placeholder.equals("{timestamp}")) losses.add(token + ": timestamp format becomes Logyard ISO-8601");
                if (conversion.equals("n") && index != pattern.length()) {
                    losses.add("embedded %n was dropped; templates render one line");
                }
                if (placeholder.isEmpty() && !conversion.equals("n")) {
                    losses.add(token + ": exception rendering uses the Logyard output policy");
                }
            }
        }
        return new PatternTranslation(template.toString(), losses, unsupported);
    }

    private static String placeholder(String conversion) {
        return switch (conversion) {
            case "d", "date" -> "{timestamp}";
            case "p", "le", "level" -> "{level}";
            case "c", "lo", "logger" -> "{logger}";
            case "m", "msg", "message" -> "{message}";
            case "t", "thread" -> "{thread}";
            case "ex", "exception", "throwable", "xEx", "xException", "rEx", "rootException", "n" -> "";
            default -> null;
        };
    }
}
