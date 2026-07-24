package com.zsumz.logyard.api.event;

import java.util.Arrays;
import java.util.function.Supplier;

/** Mutable bounded storage used exclusively while assembling an immutable {@link AttributeSet}. */
final class AttributeAccumulator {
    private String[] keys;
    private Object[] values;
    private int size;
    private boolean truncated;

    AttributeAccumulator(int expectedSize) {
        int capacity = Math.max(1, Math.min(expectedSize, CaptureLimits.MAX_ATTRIBUTES));
        keys = new String[capacity];
        values = new Object[capacity];
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

    void markTruncated() {
        truncated = true;
    }

    void put(String key, Object value) {
        int existing = indexOf(key);
        if (existing >= 0) {
            values[existing] = value;
            return;
        }
        if (isFull()) {
            truncated = true;
            return;
        }
        ensureCapacity(size + 1);
        keys[size] = key;
        values[size] = value;
        size++;
    }

    void putSupplied(String key, Supplier<?> supplier) {
        int existing = indexOf(key);
        if (existing >= 0) {
            values[existing] = supplier.get();
            return;
        }
        if (isFull()) {
            truncated = true;
            return;
        }
        put(key, supplier.get());
    }

    AttributeSet build() {
        if (truncated) {
            putTruncationMarker();
        }
        if (size == 0) {
            return AttributeSet.EMPTY;
        }
        CaptureContext context = CaptureContext.currentOrCreate();
        String[] snapshotKeys = new String[size];
        Object[] snapshotValues = new Object[size];
        int retained = 0;
        for (int index = 0; index < size; index++) {
            if (!context.claimEntry()) {
                break;
            }
            snapshotKeys[retained] = context.capturePayloadText(keys[index], CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
            snapshotValues[retained] = ValueCapture.capture(values[index], context);
            retained++;
        }
        if (retained == 0) {
            return AttributeSet.EMPTY;
        }
        return new AttributeSet(Arrays.copyOf(snapshotKeys, retained), Arrays.copyOf(snapshotValues, retained));
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
    }

    private void putTruncationMarker() {
        String key = "logyard.attributes.truncated";
        int existing = indexOf(key);
        if (existing >= 0) {
            values[existing] = true;
            return;
        }
        if (size < CaptureLimits.MAX_ATTRIBUTES) {
            ensureCapacity(size + 1);
            keys[size] = key;
            values[size] = true;
            size++;
        }
    }
}
