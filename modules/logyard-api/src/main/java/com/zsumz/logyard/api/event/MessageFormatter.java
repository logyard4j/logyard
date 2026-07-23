package com.zsumz.logyard.api.event;

import java.lang.reflect.Array;
import java.util.IdentityHashMap;

/** Small SLF4J-style brace formatter with guarded object rendering. */
public final class MessageFormatter {
    private MessageFormatter() {
    }

    public static String format(String template, Object[] arguments) {
        if (template == null) {
            return "null";
        }
        if (arguments == null || arguments.length == 0) {
            return template;
        }
        StringBuilder result = new StringBuilder(template.length() + 32);
        int cursor = 0;
        int argument = 0;
        while (argument < arguments.length) {
            int placeholder = template.indexOf("{}", cursor);
            if (placeholder < 0) {
                break;
            }
            if (placeholder > 0 && template.charAt(placeholder - 1) == '\\') {
                if (placeholder > 1 && template.charAt(placeholder - 2) == '\\') {
                    result.append(template, cursor, placeholder - 1);
                    result.append(safeToString(arguments[argument++]));
                } else {
                    result.append(template, cursor, placeholder - 1).append("{}");
                }
            } else {
                result.append(template, cursor, placeholder);
                result.append(safeToString(arguments[argument++]));
            }
            cursor = placeholder + 2;
        }
        return CaptureLimits.text(result.append(template, cursor, template.length()).toString());
    }

    public static String safeToString(Object value) {
        StringBuilder result = new StringBuilder();
        appendValue(result, value, new IdentityHashMap<>());
        return CaptureLimits.text(result.toString());
    }

    private static void appendValue(
            StringBuilder result,
            Object value,
            IdentityHashMap<Object, Boolean> visiting) {
        if (value == null) {
            result.append("null");
            return;
        }
        Class<?> type = value.getClass();
        if (!type.isArray()) {
            try {
                result.append(value);
            } catch (Throwable failure) {
                if (failure instanceof Error error) {
                    throw error;
                }
                result.append("[FAILED toString(): ")
                        .append(failure.getClass().getSimpleName())
                        .append(']');
            }
            return;
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            result.append("[...]");
            return;
        }
        try {
            result.append('[');
            int sourceLength = Array.getLength(value);
            int length = Math.min(sourceLength, CaptureLimits.MAX_COLLECTION_ELEMENTS);
            for (int index = 0; index < length; index++) {
                if (index > 0) {
                    result.append(", ");
                }
                appendValue(result, Array.get(value, index), visiting);
            }
            if (sourceLength > length) {
                result.append(", ... ").append(sourceLength - length).append(" element(s) omitted");
            }
            result.append(']');
        } finally {
            visiting.remove(value);
        }
    }
}
