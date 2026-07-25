package com.zsumz.logyard.api.event;

import java.util.Date;

/** Parses referenced arguments and bounds every substitution that recursive {@link java.text.ChoiceFormat} can expose. */
final class MessageFormatWorkBudget {
    private static final int DEFAULT_FORMATTED_VALUE_CHARS = 128;

    private MessageFormatWorkBudget() {
    }

    static Analysis analyze(String pattern, int parameterCount, int capturedParameterCount) {
        long[] referenceCounts = new long[capturedParameterCount];
        Scan scan = new Scan(pattern, parameterCount, referenceCounts);
        scan.topLevel();
        return new Analysis(referenceCounts, scan.referencedParameterOmitted, scan.nestedChoice);
    }

    record Analysis(long[] referenceCounts, boolean referencedParameterOmitted, boolean nestedChoice) {
        boolean referenced(int index) {
            return referenceCounts[index] != 0L;
        }

        boolean permits(String pattern, Object[] captured) {
            if (nestedChoice) {
                return false;
            }
            long estimate = pattern.length();
            for (int index = 0; index < referenceCounts.length; index++) {
                long count = referenceCounts[index];
                if (count == 0L) {
                    continue;
                }
                long length = formattedLength(captured[index]);
                if (count > (CaptureLimits.MAX_FORMATTER_WORK_CHARS - estimate) / Math.max(1L, length)) {
                    return false;
                }
                estimate += count * length;
            }
            return estimate <= CaptureLimits.MAX_FORMATTER_WORK_CHARS;
        }
    }

    private static final class Scan {
        private final String pattern;
        private final int parameterCount;
        private final long[] referenceCounts;
        private boolean referencedParameterOmitted;
        private boolean nestedChoice;

        private Scan(String pattern, int parameterCount, long[] referenceCounts) {
            this.pattern = pattern;
            this.parameterCount = parameterCount;
            this.referenceCounts = referenceCounts;
        }

        private void topLevel() {
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
                    count(argumentIndex(cursor + 1));
                    int style = choiceStyleStart(cursor + 1, close);
                    if (style >= 0) {
                        scanChoiceStyle(style, close);
                    }
                    if (close >= 0) {
                        cursor = close;
                    }
                }
            }
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

        private int choiceStyleStart(int elementStart, int elementEnd) {
            if (elementEnd < 0) {
                return -1;
            }
            int firstComma = pattern.indexOf(',', elementStart);
            if (firstComma < 0 || firstComma >= elementEnd) {
                return -1;
            }
            int secondComma = pattern.indexOf(',', firstComma + 1);
            if (secondComma < 0 || secondComma >= elementEnd) {
                return -1;
            }
            String formatType = pattern.substring(firstComma + 1, secondComma).trim();
            return "choice".equalsIgnoreCase(formatType) ? secondComma + 1 : -1;
        }

        private void scanChoiceStyle(int start, int end) {
            for (int cursor = start; cursor < end; cursor++) {
                if (pattern.charAt(cursor) != '{') {
                    continue;
                }
                int argumentIndex = argumentIndex(cursor + 1);
                if (argumentIndex < 0) {
                    continue;
                }
                count(argumentIndex);
                int close = elementEnd(cursor);
                if (close > cursor && choiceStyleStart(cursor + 1, close) >= 0) {
                    nestedChoice = true;
                }
            }
        }

        private int argumentIndex(int cursor) {
            while (cursor < pattern.length() && Character.isWhitespace(pattern.charAt(cursor))) {
                cursor++;
            }
            int start = cursor;
            int value = 0;
            while (cursor < pattern.length() && Character.isDigit(pattern.charAt(cursor))) {
                int digit = pattern.charAt(cursor++) - '0';
                if (value > (Integer.MAX_VALUE - digit) / 10) {
                    return -1;
                }
                value = value * 10 + digit;
            }
            return cursor == start ? -1 : value;
        }

        private void count(int argumentIndex) {
            if (argumentIndex < 0) {
                return;
            }
            if (argumentIndex < referenceCounts.length) {
                referenceCounts[argumentIndex]++;
            } else if (argumentIndex < parameterCount) {
                referencedParameterOmitted = true;
            }
        }
    }

    private static int formattedLength(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof CharSequence sequence) {
            return sequence.length();
        }
        if (value instanceof Character) {
            return 1;
        }
        if (value instanceof Boolean) {
            return 5;
        }
        if (value instanceof Date) {
            return DEFAULT_FORMATTED_VALUE_CHARS;
        }
        return Math.max(DEFAULT_FORMATTED_VALUE_CHARS, String.valueOf(value).length());
    }
}
