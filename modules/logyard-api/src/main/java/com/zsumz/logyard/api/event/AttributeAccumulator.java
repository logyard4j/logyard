package com.zsumz.logyard.api.event;

import java.util.Arrays;
import java.util.function.Supplier;

/** Mutable bounded storage used exclusively while assembling an immutable {@link AttributeSet}. */
final class AttributeAccumulator {
    private String[] keys;
    private String[] originals;
    private Object[] values;
    private int size;
    private boolean capacityTruncated;
    private boolean captureTruncated;

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
        return capacityTruncated || captureTruncated;
    }

    void markTruncated() {
        capacityTruncated = true;
        captureTruncated = true;
    }

    void markCaptureTruncated() {
        captureTruncated = true;
    }

    void put(String original, String canonicalKey, boolean keyTruncated, Object value) {
        String storageKey = resolveStorageKey(original, canonicalKey, keyTruncated);
        if (storageKey == null) {
            return;
        }
        int existing = indexOf(storageKey);
        if (existing >= 0) {
            values[existing] = value;
            return;
        }
        if (isFull()) {
            markTruncated();
            return;
        }
        ensureCapacity(size + 1);
        keys[size] = storageKey;
        if (keyTruncated) {
            ensureOriginalStorage();
            originals[size] = original;
        }
        values[size] = value;
        size++;
        captureTruncated |= keyTruncated;
    }

    void putCaptured(String key, Object value) {
        put(key, key, false, value);
    }

    void putSupplied(
            String original,
            String canonicalKey,
            boolean keyTruncated,
            Supplier<?> supplier) {
        String storageKey = resolveStorageKey(original, canonicalKey, keyTruncated);
        if (storageKey == null) {
            return;
        }
        int existing = indexOf(storageKey);
        if (existing >= 0) {
            values[existing] = supplier.get();
            return;
        }
        if (isFull()) {
            markTruncated();
            return;
        }
        put(original, canonicalKey, keyTruncated, supplier.get());
    }

    AttributeSet build() {
        if (capacityTruncated) {
            putTruncationMarker();
        }
        if (size == 0 && !captureTruncated) {
            return AttributeSet.EMPTY;
        }
        CaptureContext context = CaptureContext.currentOrCreate();
        String[] snapshotKeys = new String[size];
        Object[] snapshotValues = new Object[size];
        int retained = 0;
        for (int index = 0; index < size; index++) {
            if (context.remainingPayloadCharacters() < keys[index].length()) {
                context.markTruncated();
                break;
            }
            if (!context.claimEntry()) {
                break;
            }
            snapshotKeys[retained] = context.capturePayloadText(keys[index], CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
            snapshotValues[retained] = ValueCapture.capture(values[index], context);
            retained++;
        }
        boolean capturedTruncation = captureTruncated || context.truncated();
        return new AttributeSet(
                Arrays.copyOf(snapshotKeys, retained),
                Arrays.copyOf(snapshotValues, retained),
                capturedTruncation);
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
        if (originals != null) {
            originals = Arrays.copyOf(originals, next);
        }
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
            if (originals != null) {
                originals[size] = key;
            }
            values[size] = true;
            size++;
        }
    }

    private String resolveStorageKey(String original, String canonicalKey, boolean keyTruncated) {
        int canonical = indexOf(canonicalKey);
        if (canonical < 0) {
            return canonicalKey;
        }
        if (!keyTruncated) {
            return canonicalKey;
        }
        if (originals != null) {
            for (int index = 0; index < size; index++) {
                if (original.equals(originals[index])) {
                    return keys[index];
                }
            }
        }
        captureTruncated = true;
        for (int collision = 2; ; collision++) {
            String candidate = CaptureLimits.disambiguateAttributeKey(canonicalKey, collision);
            if (indexOf(candidate) < 0) {
                return candidate;
            }
        }
    }

    private void ensureOriginalStorage() {
        if (originals == null) {
            originals = new String[keys.length];
        }
    }
}
