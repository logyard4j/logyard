package com.zsumz.logyard.slf4j.internal.context;

import com.zsumz.logyard.api.event.CaptureLimits;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class MdcValueStore {
    static final int MAX_ENTRIES = CaptureLimits.MAX_ATTRIBUTES;

    private final ThreadLocal<LinkedHashMap<String, String>> values = new ThreadLocal<>();

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
        current.put(validKey, CaptureLimits.text(value));
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
        if (current.isEmpty()) {
            values.remove();
        }
    }

    void clear() {
        values.remove();
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
        Iterator<Map.Entry<String, String>> iterator = contextMap.entrySet().iterator();
        int visited = 0;
        while (iterator.hasNext()) {
            if (visited >= MAX_ENTRIES) {
                throw new IllegalArgumentException("SLF4J MDC supports at most " + MAX_ENTRIES + " entries per thread");
            }
            Map.Entry<String, String> entry = Objects.requireNonNull(iterator.next(), "contextMap entry");
            replacement.put(MdcKey.requireValid(entry.getKey()), CaptureLimits.text(entry.getValue()));
            visited++;
        }
        if (replacement.isEmpty()) {
            clear();
        } else {
            values.set(replacement);
        }
    }

    Map<String, String> current() {
        Map<String, String> current = values.get();
        return current == null ? Map.of() : current;
    }
}
