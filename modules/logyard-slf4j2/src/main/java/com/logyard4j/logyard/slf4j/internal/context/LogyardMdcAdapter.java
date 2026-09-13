package com.logyard4j.logyard.slf4j.internal.context;

import org.slf4j.spi.MDCAdapter;

import java.util.Deque;
import java.util.Map;

/**
 * Non-inheriting, bounded SLF4J MDC façade.
 *
 * <p>Map values and deque stacks use separate stores because SLF4J's {@link #clear()} contract
 * clears the map without implicitly destroying deque state.</p>
 */
public final class LogyardMdcAdapter implements MDCAdapter {
    public static final int MAX_ENTRIES = MdcValueStore.MAX_ENTRIES;
    public static final int MAX_STACK_KEYS = MdcDequeStore.MAX_KEYS;
    public static final int MAX_STACK_DEPTH = MdcDequeStore.MAX_DEPTH;

    private final MdcValueStore values = new MdcValueStore();
    private final MdcDequeStore stacks = new MdcDequeStore();

    @Override
    public void put(String key, String value) {
        values.put(key, value);
    }

    @Override
    public String get(String key) {
        return values.get(key);
    }

    @Override
    public void remove(String key) {
        values.remove(key);
    }

    @Override
    public void clear() {
        values.clear();
    }

    @Override
    public Map<String, String> getCopyOfContextMap() {
        return values.copy();
    }

    @Override
    public void setContextMap(Map<String, String> contextMap) {
        values.replace(contextMap);
    }

    @Override
    public void pushByKey(String key, String value) {
        stacks.push(key, value);
    }

    @Override
    public String popByKey(String key) {
        return stacks.pop(key);
    }

    @Override
    public Deque<String> getCopyOfDequeByKey(String key) {
        return stacks.copy(key);
    }

    @Override
    public void clearDequeByKey(String key) {
        stacks.clear(key);
    }

    Map<String, String> currentValues() {
        return values.current();
    }

    boolean valueTruncated(String key) {
        return values.valueTruncated(key);
    }

    boolean captureLossy() {
        return values.lossy();
    }
}
