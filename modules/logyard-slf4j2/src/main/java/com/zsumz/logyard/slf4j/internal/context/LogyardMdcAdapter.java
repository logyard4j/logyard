package com.zsumz.logyard.slf4j.internal.context;

import com.zsumz.logyard.api.event.CaptureLimits;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.spi.MDCAdapter;

/**
 * Non-inheriting, bounded SLF4J MDC implementation.
 *
 * <p>Map values and deque stacks deliberately use separate thread locals because SLF4J's
 * {@link #clear()} contract clears the map without implicitly destroying deque state.</p>
 */
public final class LogyardMdcAdapter implements MDCAdapter {
    public static final int MAX_ENTRIES = CaptureLimits.MAX_ATTRIBUTES;
    public static final int MAX_STACK_KEYS = 64;
    public static final int MAX_STACK_DEPTH = 64;

    private final ThreadLocal<LinkedHashMap<String, String>> values = new ThreadLocal<>();
    private final ThreadLocal<HashMap<String, ArrayDeque<String>>> stacks = new ThreadLocal<>();

    @Override
    public void put(String key, String value) {
        validateKey(key);
        LinkedHashMap<String, String> current = values.get();
        if (current == null) {
            current = new LinkedHashMap<>();
            values.set(current);
        }
        if (!current.containsKey(key) && current.size() >= MAX_ENTRIES) {
            throw new IllegalStateException(
                    "SLF4J MDC supports at most " + MAX_ENTRIES + " entries per thread");
        }
        current.put(key, CaptureLimits.text(value));
    }

    @Override
    public String get(String key) {
        validateKey(key);
        Map<String, String> current = values.get();
        return current == null ? null : current.get(key);
    }

    @Override
    public void remove(String key) {
        validateKey(key);
        LinkedHashMap<String, String> current = values.get();
        if (current == null) {
            return;
        }
        current.remove(key);
        if (current.isEmpty()) {
            values.remove();
        }
    }

    @Override
    public void clear() {
        values.remove();
    }

    @Override
    public Map<String, String> getCopyOfContextMap() {
        Map<String, String> current = values.get();
        return current == null || current.isEmpty() ? null : new LinkedHashMap<>(current);
    }

    @Override
    public void setContextMap(Map<String, String> contextMap) {
        if (contextMap == null) {
            clear();
            return;
        }
        LinkedHashMap<String, String> replacement = new LinkedHashMap<>();
        Iterator<Map.Entry<String, String>> iterator = contextMap.entrySet().iterator();
        int visited = 0;
        while (iterator.hasNext()) {
            if (visited >= MAX_ENTRIES) {
                throw new IllegalArgumentException(
                        "SLF4J MDC supports at most " + MAX_ENTRIES + " entries per thread");
            }
            Map.Entry<String, String> entry = Objects.requireNonNull(
                    iterator.next(),
                    "contextMap entry");
            String key = entry.getKey();
            validateKey(key);
            replacement.put(key, CaptureLimits.text(entry.getValue()));
            visited++;
        }
        if (replacement.isEmpty()) {
            clear();
        } else {
            values.set(replacement);
        }
    }

    @Override
    public void pushByKey(String key, String value) {
        validateKey(key);
        Objects.requireNonNull(value, "value");
        HashMap<String, ArrayDeque<String>> current = stacks.get();
        if (current == null) {
            current = new HashMap<>();
            stacks.set(current);
        }
        ArrayDeque<String> stack = current.get(key);
        if (stack == null) {
            if (current.size() >= MAX_STACK_KEYS) {
                throw new IllegalStateException(
                        "SLF4J MDC supports at most " + MAX_STACK_KEYS + " deque keys per thread");
            }
            stack = new ArrayDeque<>();
            current.put(key, stack);
        }
        if (stack.size() >= MAX_STACK_DEPTH) {
            throw new IllegalStateException(
                    "SLF4J MDC deque '" + key + "' supports at most "
                            + MAX_STACK_DEPTH + " values");
        }
        stack.push(CaptureLimits.text(value));
    }

    @Override
    public String popByKey(String key) {
        validateKey(key);
        HashMap<String, ArrayDeque<String>> current = stacks.get();
        if (current == null) {
            return null;
        }
        ArrayDeque<String> stack = current.get(key);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String result = stack.pop();
        if (stack.isEmpty()) {
            current.remove(key);
        }
        if (current.isEmpty()) {
            stacks.remove();
        }
        return result;
    }

    @Override
    public Deque<String> getCopyOfDequeByKey(String key) {
        validateKey(key);
        Map<String, ArrayDeque<String>> current = stacks.get();
        Deque<String> stack = current == null ? null : current.get(key);
        return stack == null ? null : new ArrayDeque<>(stack);
    }

    @Override
    public void clearDequeByKey(String key) {
        validateKey(key);
        HashMap<String, ArrayDeque<String>> current = stacks.get();
        if (current == null) {
            return;
        }
        current.remove(key);
        if (current.isEmpty()) {
            stacks.remove();
        }
    }

    Map<String, String> currentValues() {
        Map<String, String> current = values.get();
        return current == null ? Map.of() : current;
    }

    private static void validateKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("SLF4J MDC key must not be blank");
        }
        if (key.length() > CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS) {
            throw new IllegalArgumentException(
                    "SLF4J MDC key exceeds "
                            + CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS + " characters");
        }
    }
}
