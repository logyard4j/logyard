package com.zsumz.logyard.slf4j.internal.context;

import com.zsumz.logyard.api.event.CaptureLimits;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class MdcValueStore {
    static final int MAX_ENTRIES = CaptureLimits.MAX_ATTRIBUTES;

    private final ThreadLocal<LinkedHashMap<String, String>> values = new ThreadLocal<>();
    private final ThreadLocal<Set<String>> truncatedKeys = new ThreadLocal<>();

    void put(String key, String value) {
        String validKey = MdcKey.requireValid(key);
        LinkedHashMap<String, String> current = values.get();
        if (current == null) {
            current = new LinkedHashMap<>();
            values.set(current);
        }
        if (!current.containsKey(validKey) && current.size() >= MAX_ENTRIES) {
            throw new IllegalStateException("SLF4J MDC supports at most " + MAX_ENTRIES + " entries per thread");
        }
        String captured = CaptureLimits.text(value);
        current.put(validKey, captured);
        recordTruncation(validKey, captured != value);
    }

    String get(String key) {
        String validKey = MdcKey.requireValid(key);
        Map<String, String> current = values.get();
        return current == null ? null : current.get(validKey);
    }

    void remove(String key) {
        String validKey = MdcKey.requireValid(key);
        LinkedHashMap<String, String> current = values.get();
        if (current == null) {
            return;
        }
        current.remove(validKey);
        removeTruncation(validKey);
        if (current.isEmpty()) {
            values.remove();
        }
    }

    void clear() {
        values.remove();
        truncatedKeys.remove();
    }

    Map<String, String> copy() {
        Map<String, String> current = values.get();
        return current == null || current.isEmpty() ? null : new LinkedHashMap<>(current);
    }

    void replace(Map<String, String> contextMap) {
        if (contextMap == null) {
            clear();
            return;
        }
        LinkedHashMap<String, String> replacement = new LinkedHashMap<>();
        Set<String> replacementTruncatedKeys = new HashSet<>();
        Iterator<Map.Entry<String, String>> iterator = contextMap.entrySet().iterator();
        int visited = 0;
        while (iterator.hasNext()) {
            if (visited >= MAX_ENTRIES) {
                throw new IllegalArgumentException("SLF4J MDC supports at most " + MAX_ENTRIES + " entries per thread");
            }
            Map.Entry<String, String> entry = Objects.requireNonNull(iterator.next(), "contextMap entry");
            String key = MdcKey.requireValid(entry.getKey());
            String value = CaptureLimits.text(entry.getValue());
            replacement.put(key, value);
            if (value != entry.getValue()) {
                replacementTruncatedKeys.add(key);
            }
            visited++;
        }
        if (replacement.isEmpty()) {
            clear();
        } else {
            values.set(replacement);
            if (replacementTruncatedKeys.isEmpty()) {
                truncatedKeys.remove();
            } else {
                truncatedKeys.set(replacementTruncatedKeys);
            }
        }
    }

    Map<String, String> current() {
        Map<String, String> current = values.get();
        return current == null ? Map.of() : current;
    }

    boolean valueTruncated(String key) {
        Set<String> current = truncatedKeys.get();
        return current != null && current.contains(key);
    }

    private void recordTruncation(String key, boolean truncated) {
        if (truncated) {
            Set<String> current = truncatedKeys.get();
            if (current == null) {
                current = new HashSet<>();
                truncatedKeys.set(current);
            }
            current.add(key);
        } else {
            removeTruncation(key);
        }
    }

    private void removeTruncation(String key) {
        Set<String> current = truncatedKeys.get();
        if (current == null) {
            return;
        }
        current.remove(key);
        if (current.isEmpty()) {
            truncatedKeys.remove();
        }
    }
}
