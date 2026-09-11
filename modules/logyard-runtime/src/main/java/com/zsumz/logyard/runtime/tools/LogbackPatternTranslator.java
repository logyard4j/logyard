package com.zsumz.logyard.runtime.tools;

import java.util.ArrayList;
import java.util.List;

/** Best-effort translation of Logback layout patterns to Logyard console templates. */
final class LogbackPatternTranslator {
    private LogbackPatternTranslator() {
    }

    record Translation(String template, List<String> dropped) {
    }

    static Translation translate(String pattern) {
        StringBuilder template = new StringBuilder();
        List<String> dropped = new ArrayList<>();
        int index = 0;
        while (index < pattern.length()) {
            char character = pattern.charAt(index);
            if (character != '%') {
                if (character == '{' || character == '}') {
                    dropped.add(String.valueOf(character));
                } else {
                    template.append(character);
                }
                index++;
                continue;
            }
            index++;
            while (index < pattern.length()
                    && (pattern.charAt(index) == '-' || pattern.charAt(index) == '.'
                            || Character.isDigit(pattern.charAt(index)))) {
                index++;
            }
            int start = index;
            while (index < pattern.length() && Character.isLetter(pattern.charAt(index))) {
                index++;
            }
            String conversion = pattern.substring(start, index);
            if (index < pattern.length() && pattern.charAt(index) == '{') {
                int closing = pattern.indexOf('}', index);
                index = closing < 0 ? pattern.length() : closing + 1;
            }
            String placeholder = placeholder(conversion);
            if (placeholder == null) {
                if (!conversion.isEmpty()) {
                    dropped.add("%" + conversion);
                }
            } else if (!placeholder.isEmpty()) {
                template.append(placeholder);
            }
        }
        String result = template.toString().replaceAll(" +", " ").trim();
        if (!result.contains("{message}")) {
            result = result.isEmpty() ? "{timestamp} {level} {logger} {message}" : result + " {message}";
        }
        return new Translation(result, dropped);
    }

    private static String placeholder(String conversion) {
        return switch (conversion) {
            case "d", "date" -> "{timestamp}";
            case "p", "le", "level" -> "{level}";
            case "c", "lo", "logger" -> "{logger}";
            case "m", "msg", "message" -> "{message}";
            case "t", "thread" -> "{thread}";
            case "kvp", "mdc", "X" -> "{fields}";
            case "ex", "exception", "throwable", "xEx", "xException", "rEx", "rootException" -> "";
            case "n" -> "";
            default -> null;
        };
    }
}
