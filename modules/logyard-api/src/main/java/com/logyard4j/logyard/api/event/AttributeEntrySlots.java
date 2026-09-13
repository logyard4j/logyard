package com.logyard4j.logyard.api.event;

import java.util.Arrays;

/** Owns the parallel arrays and lazy collision metadata for one mutable attribute set. */
final class AttributeEntrySlots {
    private String[] keys;
    private String[] originals;
    private String[] normalizedKeyIdentities;
    private Object[] values;

    AttributeEntrySlots(int expectedSize) {
        int capacity = Math.max(1, Math.min(expectedSize, CaptureLimits.MAX_ATTRIBUTES));
        keys = new String[capacity];
        values = new Object[capacity];
    }

    String key(int index) {
        return keys[index];
    }

    void key(int index, String key) {
        keys[index] = key;
    }

    String original(int index) {
        return originals[index];
    }

    boolean tracksOriginals() {
        return originals != null;
    }

    String normalizedIdentity(int index) {
        return normalizedKeyIdentities == null ? null : normalizedKeyIdentities[index];
    }

    Object value(int index) {
        return values[index];
    }

    void value(int index, Object value) {
        values[index] = value;
    }

    void append(int index, String key, String original, String normalizedIdentity, Object value) {
        ensureCapacity(index + 1);
        if (normalizedIdentity != null) {
            ensureIdentityStorage(index);
            normalizedKeyIdentities[index] = normalizedIdentity;
        }
        keys[index] = key;
        if (originals != null) {
            originals[index] = original;
        }
        values[index] = value;
    }

    void ensureIdentityStorage(int size) {
        if (originals == null) {
            originals = new String[keys.length];
            System.arraycopy(keys, 0, originals, 0, size);
        }
        if (normalizedKeyIdentities == null) {
            normalizedKeyIdentities = new String[keys.length];
        }
    }

    String[] keys() {
        return keys;
    }

    String[] normalizedKeyIdentities() {
        return normalizedKeyIdentities;
    }

    Object[] values() {
        return values;
    }

    private void ensureCapacity(int needed) {
        if (needed <= keys.length) {
            return;
        }
        int next = Math.min(CaptureLimits.MAX_ATTRIBUTES, Math.max(needed, keys.length << 1));
        keys = Arrays.copyOf(keys, next);
        if (originals != null) {
            originals = Arrays.copyOf(originals, next);
        }
        if (normalizedKeyIdentities != null) {
            normalizedKeyIdentities = Arrays.copyOf(normalizedKeyIdentities, next);
        }
        values = Arrays.copyOf(values, next);
    }
}
