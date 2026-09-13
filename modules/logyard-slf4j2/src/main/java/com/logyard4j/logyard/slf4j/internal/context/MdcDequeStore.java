package com.logyard4j.logyard.slf4j.internal.context;

import com.logyard4j.logyard.api.event.CaptureLimits;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class MdcDequeStore {
    static final int MAX_KEYS = 64;
    static final int MAX_DEPTH = 64;

    private final ThreadLocal<HashMap<String, ArrayDeque<String>>> stacks = new ThreadLocal<>();

    void push(String key, String value) {
        String validKey = MdcKey.stackKey(key);
        Objects.requireNonNull(value, "value");
        HashMap<String, ArrayDeque<String>> current = stacks.get();
        if (current == null) {
            current = new HashMap<>();
            stacks.set(current);
        }
        ArrayDeque<String> stack = current.get(validKey);
        if (stack == null) {
            if (current.size() >= MAX_KEYS) {
                throw new IllegalStateException("SLF4J MDC supports at most " + MAX_KEYS + " deque keys per thread");
            }
            stack = new ArrayDeque<>();
            current.put(validKey, stack);
        }
        if (stack.size() >= MAX_DEPTH) {
            throw new IllegalStateException("SLF4J MDC deque '" + validKey + "' supports at most " + MAX_DEPTH + " values");
        }
        stack.push(CaptureLimits.text(value));
    }

    String pop(String key) {
        String validKey = MdcKey.stackKey(key);
        HashMap<String, ArrayDeque<String>> current = stacks.get();
        if (current == null) {
            return null;
        }
        ArrayDeque<String> stack = current.get(validKey);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String result = stack.pop();
        if (stack.isEmpty()) {
            current.remove(validKey);
        }
        if (current.isEmpty()) {
            stacks.remove();
        }
        return result;
    }

    Deque<String> copy(String key) {
        String validKey = MdcKey.stackKey(key);
        Map<String, ArrayDeque<String>> current = stacks.get();
        Deque<String> stack = current == null ? null : current.get(validKey);
        return stack == null ? null : new ArrayDeque<>(stack);
    }

    void clear(String key) {
        String validKey = MdcKey.stackKey(key);
        HashMap<String, ArrayDeque<String>> current = stacks.get();
        if (current == null) {
            return;
        }
        current.remove(validKey);
        if (current.isEmpty()) {
            stacks.remove();
        }
    }
}
