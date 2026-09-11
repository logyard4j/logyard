package com.zsumz.logyard.runtime.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Best-effort translation of Log4j2 PatternLayout patterns to Logyard console templates.
 *
 * <p>Log4j2 and Logback spell their conversions differently, so this table is deliberately
 * separate from {@link LogbackPatternTranslator}. Conversions that only decorate a nested
 * pattern — colouring and truncation — keep the nested pattern and report the decoration,
 * because Logyard styles the console through themes instead of the format string.</p>
 */
final class Log4j2PatternTranslator {
    /** Conversions that wrap a nested pattern Logyard renders without the decoration. */
    private static final Set<String> WRAPPERS = Set.of("highlight", "style", "notEmpty", "encode", "maxLen");
    private static final int MAX_NESTING = 8;

    private Log4j2PatternTranslator() {
    }

    record Translation(String template, List<String> dropped) {
    }

    static Translation translate(String pattern) {
        List<String> dropped = new ArrayList<>();
        String template = convert(pattern, dropped, 0).replaceAll(" +", " ").trim();
        if (!template.contains("{message}")) {
            template = template.isEmpty() ? "{timestamp} {level} {logger} {message}" : template + " {message}";
        }
        return new Translation(template, dropped);
    }

    private static String convert(String pattern, List<String> dropped, int depth) {
        StringBuilder template = new StringBuilder();
        int index = 0;
        while (index < pattern.length()) {
            char character = pattern.charAt(index);
            if (character == '%') {
                index = conversion(pattern, index + 1, template, dropped, depth);
                continue;
            }
            if (character == '{' || character == '}') {
                dropped.add(String.valueOf(character));
            } else {
                template.append(character);
            }
            index++;
        }
        return template.toString();
    }

    /** Consumes one conversion starting after its {@code %} and returns the next index. */
    private static int conversion(
            String pattern, int start, StringBuilder template, List<String> dropped, int depth) {
        if (start < pattern.length() && pattern.charAt(start) == '%') {
            template.append('%');
            return start + 1;
        }
        int index = start;
        while (index < pattern.length() && (pattern.charAt(index) == '-' || pattern.charAt(index) == '.'
                || Character.isDigit(pattern.charAt(index)))) {
            index++;
        }
        int nameStart = index;
        while (index < pattern.length() && Character.isLetter(pattern.charAt(index))) {
            index++;
        }
        String name = pattern.substring(nameStart, index);
        List<String> groups = new ArrayList<>();
        while (index < pattern.length() && pattern.charAt(index) == '{') {
            int closing = matchingBrace(pattern, index);
            groups.add(pattern.substring(index + 1, closing < 0 ? pattern.length() : closing));
            index = closing < 0 ? pattern.length() : closing + 1;
        }
        append(name, groups, template, dropped, depth);
        return index;
    }

    private static void append(
            String name, List<String> groups, StringBuilder template, List<String> dropped, int depth) {
        String placeholder = placeholder(name);
        if (placeholder != null) {
            template.append(placeholder);
            return;
        }
        if (name.isEmpty()) {
            return;
        }
        dropped.add(WRAPPERS.contains(name) ? "%" + name + " (decoration)" : "%" + name);
        if (WRAPPERS.contains(name) && !groups.isEmpty() && depth < MAX_NESTING) {
            template.append(convert(groups.getFirst(), dropped, depth + 1));
        }
    }

    private static int matchingBrace(String pattern, int opening) {
        int depth = 0;
        for (int index = opening; index < pattern.length(); index++) {
            char character = pattern.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static String placeholder(String conversion) {
        return switch (conversion) {
            case "d", "date" -> "{timestamp}";
            case "p", "level" -> "{level}";
            case "c", "logger" -> "{logger}";
            case "m", "msg", "message" -> "{message}";
            case "t", "tn", "thread", "threadName" -> "{thread}";
            case "X", "mdc", "MDC" -> "{fields}";
            case "n", "ex", "exception", "throwable",
                 "xEx", "xThrowable", "rEx", "rThrowable" -> "";
            default -> null;
        };
    }
}
