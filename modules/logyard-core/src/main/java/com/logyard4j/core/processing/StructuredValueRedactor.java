package com.logyard4j.core.processing;

import com.logyard4j.api.event.CaptureLimits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Copy-on-first-match traversal with an independent event-wide redaction budget. */
final class StructuredValueRedactor {
    private final PathMatcher matcher;
    private final Object replacement;
    private final StringBuilder path = new StringBuilder(CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
    private int remaining = CaptureLimits.MAX_EVENT_ENTRIES;
    private boolean truncated;

    StructuredValueRedactor(PathMatcher matcher, Object replacement) {
        this.matcher = matcher;
        this.replacement = replacement;
    }

    Result redactChildren(Object value, String path) {
        this.path.setLength(0);
        this.path.append(path);
        Object redacted;
        if (value instanceof Map<?, ?> map) {
            redacted = redactMap(map, 0);
        } else if (value instanceof List<?> list) {
            redacted = redactList(list, 0);
        } else {
            redacted = value;
        }
        return new Result(redacted, truncated);
    }

    private Object redactMap(Map<?, ?> source, int depth) {
        if (depth >= CaptureLimits.MAX_NESTING_DEPTH) {
            truncated = true;
            return replacement;
        }
        if (!claimContainer()) {
            return replacement;
        }
        Map<Object, Object> copy = null;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!claim()) {
                return replacement;
            }
            String key = String.valueOf(entry.getKey());
            int parentLength = path.length();
            if (parentLength > 0) {
                path.append('.');
            }
            path.append(key);
            Object current = entry.getValue();
            Object redacted;
            try {
                redacted = matcher.matches(path, key)
                        ? replacement
                        : redactChildren(current, depth + 1);
            } finally {
                path.setLength(parentLength);
            }
            if (redacted != current && copy == null) {
                copy = new LinkedHashMap<>(source);
            }
            if (copy != null) {
                copy.put(entry.getKey(), redacted);
            }
        }
        return copy == null ? source : Collections.unmodifiableMap(copy);
    }

    private Object redactList(List<?> source, int depth) {
        if (depth >= CaptureLimits.MAX_NESTING_DEPTH) {
            truncated = true;
            return replacement;
        }
        if (!claimContainer()) {
            return replacement;
        }
        List<Object> copy = null;
        for (int index = 0; index < source.size(); index++) {
            if (!claim()) {
                return replacement;
            }
            Object current = source.get(index);
            int parentLength = path.length();
            path.append('[').append(index).append(']');
            Object redacted;
            try {
                redacted = matcher.matches(path, "")
                        ? replacement
                        : redactChildren(current, depth + 1);
            } finally {
                path.setLength(parentLength);
            }
            if (redacted != current && copy == null) {
                copy = new ArrayList<>(source);
            }
            if (copy != null) {
                copy.set(index, redacted);
            }
        }
        return copy == null ? source : Collections.unmodifiableList(copy);
    }

    private Object redactChildren(Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            return redactMap(map, depth);
        }
        if (value instanceof List<?> list) {
            return redactList(list, depth);
        }
        return value;
    }

    private boolean claimContainer() {
        if (remaining == 0) {
            truncated = true;
            return false;
        }
        return true;
    }

    private boolean claim() {
        if (remaining == 0) {
            truncated = true;
            return false;
        }
        remaining--;
        return true;
    }

    record Result(Object value, boolean truncated) {
    }

    @FunctionalInterface
    interface PathMatcher {
        boolean matches(CharSequence path, String leaf);
    }
}
