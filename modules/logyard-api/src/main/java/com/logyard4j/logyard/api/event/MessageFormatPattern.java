package com.logyard4j.logyard.api.event;

import java.text.ChoiceFormat;
import java.text.Format;
import java.util.ArrayList;
import java.util.List;

/** Lightweight structural scan used before recursively parsing a selected choice branch. */
final class MessageFormatPattern {
    private MessageFormatPattern() {
    }

    static List<Element> parse(String pattern) {
        return new Scanner(pattern).elements();
    }

    static String sanitizeDateTime(String pattern, List<Element> elements) {
        if (!containsDateTime(elements)) {
            return pattern;
        }
        StringBuilder sanitized = new StringBuilder(pattern.length());
        int copied = 0;
        for (Element element : elements) {
            if (!element.dateTime()) {
                continue;
            }
            sanitized.append(pattern, copied, element.opening());
            sanitized.append('{').append(element.argumentIndex()).append('}');
            copied = element.closing() + 1;
        }
        return sanitized.append(pattern, copied, pattern.length()).toString();
    }

    static boolean containsDateTime(List<Element> elements) {
        for (Element element : elements) {
            if (element.dateTime()) {
                return true;
            }
        }
        return false;
    }

    record Element(
            int argumentIndex,
            int sourceCharacters,
            String formatType,
            String formatStyle,
            int opening,
            int closing) {
        Format choiceFormat() {
            return "choice".equalsIgnoreCase(formatType) ? new ChoiceFormat(formatStyle) : null;
        }

        boolean dateTime() {
            return "date".equalsIgnoreCase(formatType) || "time".equalsIgnoreCase(formatType);
        }
    }

    private static final class Scanner {
        private final String pattern;

        private Scanner(String pattern) {
            this.pattern = pattern;
        }

        private List<Element> elements() {
            List<Element> elements = new ArrayList<>();
            boolean quoted = false;
            for (int cursor = 0; cursor < pattern.length(); cursor++) {
                char current = pattern.charAt(cursor);
                if (current == '\'') {
                    if (cursor + 1 < pattern.length() && pattern.charAt(cursor + 1) == '\'') {
                        cursor++;
                    } else {
                        quoted = !quoted;
                    }
                    continue;
                }
                if (!quoted && current == '{') {
                    int close = elementEnd(cursor);
                    if (close < 0) {
                        throw new IllegalArgumentException("unmatched MessageFormat element");
                    }
                    elements.add(element(cursor, close));
                    cursor = close;
                }
            }
            return elements;
        }

        private Element element(int opening, int close) {
            int firstComma = delimiter(opening + 1, close);
            if (firstComma < 0) {
                return new Element(argumentIndex(opening + 1, close), close - opening + 1, "", "", opening, close);
            }
            int secondComma = delimiter(firstComma + 1, close);
            String type = pattern.substring(firstComma + 1, secondComma < 0 ? close : secondComma).trim();
            String style = secondComma < 0 ? "" : pattern.substring(secondComma + 1, close);
            return new Element(argumentIndex(opening + 1, firstComma), close - opening + 1, type, style, opening, close);
        }

        private int delimiter(int start, int end) {
            boolean quoted = false;
            int depth = 0;
            for (int cursor = start; cursor < end; cursor++) {
                char current = pattern.charAt(cursor);
                if (current == '\'') {
                    if (cursor + 1 < end && pattern.charAt(cursor + 1) == '\'') {
                        cursor++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (!quoted && current == '{') {
                    depth++;
                } else if (!quoted && current == '}') {
                    depth--;
                } else if (!quoted && depth == 0 && current == ',') {
                    return cursor;
                }
            }
            return -1;
        }

        private int elementEnd(int opening) {
            int depth = 1;
            boolean quoted = false;
            for (int cursor = opening + 1; cursor < pattern.length(); cursor++) {
                char current = pattern.charAt(cursor);
                if (current == '\'') {
                    if (cursor + 1 < pattern.length() && pattern.charAt(cursor + 1) == '\'') {
                        cursor++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (!quoted && current == '{') {
                    depth++;
                } else if (!quoted && current == '}' && --depth == 0) {
                    return cursor;
                }
            }
            return -1;
        }

        private int argumentIndex(int start, int end) {
            try {
                return Integer.parseInt(pattern, start, end, 10);
            } catch (NumberFormatException invalid) {
                return -1;
            }
        }
    }
}
