package com.logyard4j.slf4j.internal.context;

import com.logyard4j.api.event.CaptureLimits;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded per-thread MDC map that drops excess entries without throwing.
 *
 * <p>A put beyond the entry bound and the tail of an oversized {@code setContextMap}
 * are dropped; the loss is reported to event capture so accepted events
 * carry the capture-truncation flag instead of the application failing to log.</p>
 */
final class MdcValueStore {
    static final int MAX_ENTRIES = CaptureLimits.MAX_ATTRIBUTES;

    private final ThreadLocal<LinkedHashMap<String, String>> values = new ThreadLocal<>();
    private final ThreadLocal<Set<String>> truncatedKeys = new ThreadLocal<>();
    private final ThreadLocal<Boolean> droppedEntries = new ThreadLocal<>();

    void put(String key, String value) {
        String validKey = MdcKey.bounded(key);
        if (validKey == null) {
            droppedEntries.set(Boolean.TRUE);
            return;
        }
        LinkedHashMap<String, String> current = values.get();
        if (current == null) {
            current = new LinkedHashMap<>();
            values.set(current);
        }
        if (!current.containsKey(validKey) && current.size() >= MAX_ENTRIES) {
            droppedEntries.set(Boolean.TRUE);
            return;
        }
        String captured = CaptureLimits.text(value);
        current.put(validKey, captured);
        recordTruncation(validKey, captured != value);
    }

    String get(String key) {
        String validKey = MdcKey.bounded(key);
        Map<String, String> current = values.get();
        return current == null ? null : current.get(validKey);
    }

    void remove(String key) {
        String validKey = MdcKey.bounded(key);
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
        droppedEntries.remove();
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
        boolean dropped = false;
        int visited = 0;
        Iterator<Map.Entry<String, String>> iterator = contextMap.entrySet().iterator();
        while (visited < MAX_ENTRIES && iterator.hasNext()) {
            visited++;
            Map.Entry<String, String> entry = Objects.requireNonNull(iterator.next(), "contextMap entry");
            String key = MdcKey.bounded(entry.getKey());
            if (key == null) {
                dropped = true;
                continue;
            }
            String originalValue = entry.getValue();
            String value = CaptureLimits.text(originalValue);
            replacement.put(key, value);
            if (value != originalValue) {
                replacementTruncatedKeys.add(key);
            }
        }
        dropped |= iterator.hasNext();
        if (replacement.isEmpty()) {
            clear();
        } else {
            values.set(replacement);
            if (replacementTruncatedKeys.isEmpty()) {
                truncatedKeys.remove();
            } else {
                truncatedKeys.set(replacementTruncatedKeys);
            }
            droppedEntries.remove();
        }
        if (dropped) {
            droppedEntries.set(Boolean.TRUE);
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

    /** Reports whether entries were dropped since this thread's MDC was last cleared. */
    boolean lossy() {
        return droppedEntries.get() == Boolean.TRUE;
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
