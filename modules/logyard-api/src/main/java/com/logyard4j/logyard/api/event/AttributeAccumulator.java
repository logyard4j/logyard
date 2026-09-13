package com.logyard4j.logyard.api.event;

import java.util.function.Supplier;

/** Mutable bounded storage used exclusively while assembling an immutable {@link AttributeSet}. */
final class AttributeAccumulator {
    private final AttributeEntryStorage entries;
    private boolean capacityTruncated;

    AttributeAccumulator(int expectedSize) {
        entries = new AttributeEntryStorage(expectedSize);
    }

    int size() {
        return entries.size();
    }

    boolean isFull() {
        return entries.isFull();
    }

    boolean truncated() {
        return capacityTruncated || entries.captureTruncated();
    }

    void markTruncated() {
        capacityTruncated = true;
        entries.markCaptureTruncated();
    }

    void markCaptureTruncated() {
        entries.markCaptureTruncated();
    }

    void put(String original, String canonicalKey, boolean keyTruncated, Object value) {
        if (!entries.put(original, canonicalKey, keyTruncated, value)) {
            markTruncated();
        }
    }

    void putCaptured(String key, String normalizedIdentity, Object value) {
        if (!entries.putCaptured(key, normalizedIdentity, value)) {
            markTruncated();
        }
    }

    void replaceCapturedValue(String key, Object value) {
        entries.replaceCapturedValue(key, value);
    }

    void putSupplied(
            String original,
            String canonicalKey,
            boolean keyTruncated,
            Supplier<?> supplier) {
        if (entries.replaceOriginal(original, canonicalKey, keyTruncated, supplier)) {
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
            entries.putTruncationMarker();
        }
        if (entries.size() == 0 && !entries.captureTruncated()) {
            return AttributeSet.EMPTY;
        }
        return AttributeSnapshotCapture.capture(
                entries.keys(),
                entries.normalizedKeyIdentities(),
                entries.values(),
                entries.size(),
                entries.captureTruncated());
    }
}
