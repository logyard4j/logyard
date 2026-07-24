package com.zsumz.logyard.core.processing;

import com.zsumz.logyard.api.event.CaptureLimits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Copy-on-first-match traversal of already captured map and list values. */
final class StructuredValueRedactor {
    private final PathMatcher matcher;
    private final Object replacement;
    private final IdentityHashMap<Object, Map<String, Object>> completed = new IdentityHashMap<>();
    private int remaining;

    StructuredValueRedactor(PathMatcher matcher, Object replacement, int remaining) {
        this.matcher = matcher;
        this.replacement = replacement;
        this.remaining = Math.max(0, Math.min(remaining, CaptureLimits.MAX_EVENT_ENTRIES));
    }

    Object redactChildren(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            return redactMap(map, path);
        }
        if (value instanceof List<?> list) {
            return redactList(list, path);
        }
        return value;
    }

    private Object redactMap(Map<?, ?> source, String path) {
        Object cached = completed(source, path);
        if (cached != null) {
            return cached;
        }
        Map<Object, Object> copy = null;
        int index = 0;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!claim()) {
                remember(source, path, replacement);
                return replacement;
            }
            String key = String.valueOf(entry.getKey());
            String childPath = path.isEmpty() ? key : path + '.' + key;
            Object current = entry.getValue();
            Object redacted = matcher.matches(childPath, key) ? replacement : redactChildren(current, childPath);
            if (redacted != current && copy == null) {
                copy = new LinkedHashMap<>(source.size());
                int retained = 0;
                for (Map.Entry<?, ?> original : source.entrySet()) {
                    if (retained++ == index) {
                        break;
                    }
                    if (!claim()) {
                        remember(source, path, replacement);
                        return replacement;
                    }
                    copy.put(original.getKey(), original.getValue());
                }
            }
            if (copy != null) {
                copy.put(entry.getKey(), redacted);
            }
            index++;
        }
        Object result = copy == null ? source : Collections.unmodifiableMap(copy);
        remember(source, path, result);
        return result;
    }

    private Object redactList(List<?> source, String path) {
        Object cached = completed(source, path);
        if (cached != null) {
            return cached;
        }
        List<Object> copy = null;
        for (int index = 0; index < source.size(); index++) {
            if (!claim()) {
                remember(source, path, replacement);
                return replacement;
            }
            Object current = source.get(index);
            String childPath = path + '[' + index + ']';
            Object redacted = matcher.matches(childPath, "") ? replacement : redactChildren(current, childPath);
            if (redacted != current && copy == null) {
                copy = new ArrayList<>(source);
            }
            if (copy != null) {
                copy.set(index, redacted);
            }
        }
        Object result = copy == null ? source : Collections.unmodifiableList(copy);
        remember(source, path, result);
        return result;
    }

    private boolean claim() {
        if (remaining == 0) {
            return false;
        }
        remaining--;
        return true;
    }

    private Object completed(Object source, String path) {
        Map<String, Object> paths = completed.get(source);
        return paths == null ? null : paths.get(path);
    }

    private void remember(Object source, String path, Object result) {
        completed.computeIfAbsent(source, ignored -> new HashMap<>()).put(path, result);
    }

    @FunctionalInterface
    interface PathMatcher {
        boolean matches(String path, String leaf);
    }
}
