package com.zsumz.logyard.api.event;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Mutable assembly state shared by the public fluent attribute builder. */
final class AttributeSetBuilderState {
    private final AttributeAccumulator attributes;
    private final boolean systemAttributesAllowed;

    AttributeSetBuilderState(int expectedSize, boolean systemAttributesAllowed) {
        attributes = new AttributeAccumulator(expectedSize);
        this.systemAttributesAllowed = systemAttributesAllowed;
    }

    void put(String key, Object value) {
        String storageKey = normalizeKey(key);
        attributes.put(key, storageKey, storageKey != key, value);
    }

    void putSupplied(String key, Supplier<?> supplier) {
        String storageKey = normalizeKey(key);
        attributes.putSupplied(key, storageKey, storageKey != key, Objects.requireNonNull(supplier, "valueSupplier"));
    }

    int size() {
        return attributes.size();
    }

    boolean isFull() {
        return attributes.isFull();
    }

    void markTruncated() {
        attributes.markTruncated();
    }

    void markCaptureTruncated() {
        attributes.markCaptureTruncated();
    }

    void putAll(AttributeSet source) {
        for (int index = 0; index < source.size(); index++) {
            attributes.putCaptured(
                    source.keyAt(index),
                    source.normalizedKeyIdentities == null ? null : source.normalizedKeyIdentities[index],
                    source.valueAt(index));
        }
        if (source.captureTruncated) {
            attributes.markCaptureTruncated();
        }
    }

    void replaceCapturedValue(String key, Object value) {
        attributes.replaceCapturedValue(key, value);
    }

    void putCaptured(AttributeSet source, int index) {
        Objects.requireNonNull(source, "source");
        attributes.putCaptured(source.keyAt(index),
                source.normalizedKeyIdentities == null ? null : source.normalizedKeyIdentities[index],
                source.valueAt(index));
        if (source.captureTruncated) attributes.markCaptureTruncated();
    }

    void putAll(Map<String, ?> source) {
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            put(entry.getKey(), entry.getValue());
            if (attributes.truncated() && isFull()) {
                return;
            }
        }
    }

    void putNormalized(NormalizedAttributeKey key, Object value) {
        NormalizedAttributeKey normalized = Objects.requireNonNull(key, "key");
        attributes.put(normalized.original(), normalized.storageKey(), normalized.truncated(), value);
    }

    AttributeSet build() {
        return attributes.build();
    }

    private String normalizeKey(String key) {
        return AttributeKey.normalizeStorage(key, systemAttributesAllowed);
    }
}
