package com.zsumz.logyard.runtime.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Translates supported Log4j2 patterns and reports every discarded option or wrapper. */
final class Log4j2PatternTranslator {
    /** Unsupported wrappers whose inner pattern can still be shown in a reviewable draft. */
    private static final Set<String> WRAPPERS = Set.of("highlight", "style", "notEmpty", "encode", "maxLen");
    private static final int MAX_NESTING = 8;

    private Log4j2PatternTranslator() {
    }

    static PatternTranslation translate(String pattern) {
        List<String> losses = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        if (!pattern.endsWith("%n")) losses.add("a final record newline was added by the console output");
        String template = convert(pattern, losses, unsupported, 0);
        return new PatternTranslation(template, losses, unsupported);
    }

    private static String convert(String pattern, List<String> losses, List<String> unsupported, int depth) {
        StringBuilder template = new StringBuilder();
        int index = 0;
        while (index < pattern.length()) {
            char character = pattern.charAt(index);
            if (character == '%') {
                index = conversion(pattern, index + 1, template, losses, unsupported, depth);
                continue;
            }
            template.append(character);
            if (character == '{' || character == '}') template.append(character);
            index++;
        }
        return template.toString();
    }

    /** Consumes one conversion starting after its {@code %} and returns the next index. */
    private static int conversion(
            String pattern, int start, StringBuilder template,
            List<String> losses, List<String> unsupported, int depth) {
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
        String token = pattern.substring(start - 1, index);
        if (nameStart != start || !groups.isEmpty()) losses.add(token + ": width or options were not preserved");
        if (name.equals("n") && index != pattern.length()) losses.add("embedded %n was dropped; templates render one line");
        append(name, groups, token, template, losses, unsupported, depth);
        return index;
    }

    private static void append(
            String name, List<String> groups, String token, StringBuilder template,
            List<String> losses, List<String> unsupported, int depth) {
        String placeholder = placeholder(name);
        if (placeholder != null) {
            template.append(placeholder);
            if (placeholder.equals("{timestamp}")) losses.add(token + ": timestamp format becomes Logyard ISO-8601");
            if (placeholder.isEmpty() && !name.equals("n")) {
                losses.add(token + ": exception rendering uses the Logyard output policy");
            }
            return;
        }
        if (name.equals("X") || name.equals("mdc") || name.equals("MDC")) {
            unsupported.add(token + ": context selection is unsupported; no fields or MDC capture were added");
        } else {
            unsupported.add(token + " has no template equivalent and was dropped");
        }
        if (WRAPPERS.contains(name) && !groups.isEmpty() && depth < MAX_NESTING) {
            template.append(convert(groups.getFirst(), losses, unsupported, depth + 1));
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
            case "n", "ex", "exception", "throwable",
                 "xEx", "xThrowable", "rEx", "rThrowable" -> "";
            default -> null;
        };
    }
}
