package com.zsumz.logyard.api.event;

import java.util.Arrays;
import java.util.function.Supplier;

/** Mutable bounded storage used exclusively while assembling an immutable {@link AttributeSet}. */
final class AttributeAccumulator {
    private String[] keys;
    private Object[] values;
    private boolean[] captured;
    private int size;
    private boolean truncated;

    AttributeAccumulator(int expectedSize) {
        int capacity = Math.max(1, Math.min(expectedSize, CaptureLimits.MAX_ATTRIBUTES));
        keys = new String[capacity];
        values = new Object[capacity];
        captured = new boolean[capacity];
    }

    int size() {
        return size;
    }

    boolean isFull() {
        return size >= CaptureLimits.MAX_ATTRIBUTES - 1;
    }

    boolean truncated() {
        return truncated;
    }

    void put(String key, Object value, boolean alreadyCaptured) {
        int existing = indexOf(key);
        if (existing >= 0) {
            values[existing] = value;
            captured[existing] = alreadyCaptured;
            return;
        }
        if (isFull()) {
            truncated = true;
            return;
        }
        ensureCapacity(size + 1);
        keys[size] = key;
        values[size] = value;
        captured[size] = alreadyCaptured;
        size++;
    }

    void putSupplied(String key, Supplier<?> supplier) {
        int existing = indexOf(key);
        if (existing >= 0) {
            values[existing] = supplier.get();
            captured[existing] = false;
            return;
        }
        if (isFull()) {
            truncated = true;
            return;
        }
        put(key, supplier.get(), false);
    }

    AttributeSet build() {
        if (truncated) {
            putTruncationMarker();
        }
        if (size == 0) {
            return AttributeSet.EMPTY;
        }
        Object[] snapshot = new Object[size];
        for (int index = 0; index < size; index++) {
            snapshot[index] = captured[index] ? values[index] : ValueCapture.capture(values[index]);
        }
        return new AttributeSet(Arrays.copyOf(keys, size), snapshot);
    }

    private int indexOf(String key) {
        for (int index = 0; index < size; index++) {
            if (keys[index].equals(key)) {
                return index;
            }
        }
        return -1;
    }

    private void ensureCapacity(int needed) {
        if (needed <= keys.length) {
            return;
        }
        int next = Math.min(CaptureLimits.MAX_ATTRIBUTES, Math.max(needed, keys.length << 1));
        keys = Arrays.copyOf(keys, next);
        values = Arrays.copyOf(values, next);
        captured = Arrays.copyOf(captured, next);
    }

    private void putTruncationMarker() {
        String key = "logyard.attributes.truncated";
        int existing = indexOf(key);
        if (existing >= 0) {
            values[existing] = true;
            captured[existing] = true;
            return;
        }
        if (size < CaptureLimits.MAX_ATTRIBUTES) {
            ensureCapacity(size + 1);
            keys[size] = key;
            values[size] = true;
            captured[size] = true;
            size++;
        }
    }
}
