package com.zsumz.logyard.api.event;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Compact insertion-ordered immutable attributes with typed values. */
public final class AttributeSet {
    public static final AttributeSet EMPTY = new AttributeSet(new String[0], new Object[0]);

    private final String[] keys;
    private final Object[] values;

    private AttributeSet(String[] keys, Object[] values) {
        this.keys = keys;
        this.values = values;
    }

    public int size() {
        return keys.length;
    }

    public boolean isEmpty() {
        return keys.length == 0;
    }

    public String keyAt(int index) {
        return keys[index];
    }

    public Object valueAt(int index) {
        return values[index];
    }

    public Object get(String key) {
        for (int index = keys.length - 1; index >= 0; index--) {
            if (keys[index].equals(key)) {
                return values[index];
            }
        }
        return null;
    }

    public void forEach(BiConsumer<String, Object> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (int index = 0; index < keys.length; index++) {
            consumer.accept(keys[index], values[index]);
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        forEach(result::put);
        return result;
    }

    AttributeSet withSystemAttribute(String key, Object value) {
        Objects.requireNonNull(key, "key");
        String normalized = CaptureLimits.attributeKey(key);
        Object capturedValue = ValueCapture.capture(value);
        for (int index = 0; index < keys.length; index++) {
            if (keys[index].equals(normalized)) {
                Object[] nextValues = values.clone();
                nextValues[index] = capturedValue;
                return new AttributeSet(keys.clone(), nextValues);
            }
        }
        if (keys.length < CaptureLimits.MAX_ATTRIBUTES) {
            String[] nextKeys = Arrays.copyOf(keys, keys.length + 1);
            Object[] nextValues = Arrays.copyOf(values, values.length + 1);
            nextKeys[keys.length] = normalized;
            nextValues[values.length] = capturedValue;
            return new AttributeSet(nextKeys, nextValues);
        }
        String[] nextKeys = keys.clone();
        Object[] nextValues = values.clone();
        int replacement = keys.length - 1;
        for (int index = keys.length - 1; index >= 0; index--) {
            if (!keys[index].startsWith("logyard.")) {
                replacement = index;
                break;
            }
        }
        nextKeys[replacement] = normalized;
        nextValues[replacement] = capturedValue;
        return new AttributeSet(nextKeys, nextValues);
    }

    public AttributeSet mergedWith(AttributeSet other) {
        Objects.requireNonNull(other, "other");
        if (other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        return builder(size() + other.size()).putAll(this).putAll(other).build();
    }

    public static Builder builder() {
        return new Builder(8);
    }

    public static Builder builder(int expectedSize) {
        return new Builder(expectedSize);
    }

    public static AttributeSet of(String key, Object value) {
        return builder(1).put(key, value).build();
    }

    public static final class Builder {
        private String[] keys;
        private Object[] values;
        private boolean[] captured;
        private int size;
        private boolean truncated;

        private Builder(int expectedSize) {
            int capacity = Math.max(1, Math.min(expectedSize, CaptureLimits.MAX_ATTRIBUTES));
            keys = new String[capacity];
            values = new Object[capacity];
            captured = new boolean[capacity];
        }

        public Builder put(String key, Object value) {
            return putValue(normalizeKey(key), value, false);
        }

        /**
         * Evaluates and captures a value supplier while building an enabled event.
         * The supplier is never retained by the resulting immutable attribute set.
         */
        public Builder putSupplied(String key, Supplier<?> supplier) {
            String normalized = normalizeKey(key);
            Objects.requireNonNull(supplier, "valueSupplier");
            for (int index = 0; index < size; index++) {
                if (keys[index].equals(normalized)) {
                    values[index] = supplier.get();
                    captured[index] = false;
                    return this;
                }
            }
            if (size >= CaptureLimits.MAX_ATTRIBUTES - 1) {
                truncated = true;
                return this;
            }
            return putValue(normalized, supplier.get(), false);
        }

        public int size() {
            return size;
        }

        public boolean isFull() {
            return size >= CaptureLimits.MAX_ATTRIBUTES - 1;
        }

        public Builder putAll(AttributeSet attributes) {
            Objects.requireNonNull(attributes, "attributes");
            for (int index = 0; index < attributes.size(); index++) {
                putValue(attributes.keyAt(index), attributes.valueAt(index), true);
            }
            return this;
        }

        public Builder putAll(Map<String, ?> attributes) {
            Objects.requireNonNull(attributes, "attributes");
            for (Map.Entry<String, ?> entry : attributes.entrySet()) {
                put(entry.getKey(), entry.getValue());
                if (truncated && isFull()) {
                    break;
                }
            }
            return this;
        }

        public AttributeSet build() {
            if (truncated) {
                putTruncationMarker();
            }
            if (size == 0) {
                return EMPTY;
            }
            Object[] snapshot = new Object[size];
            for (int index = 0; index < size; index++) {
                snapshot[index] = captured[index] ? values[index] : ValueCapture.capture(values[index]);
            }
            return new AttributeSet(Arrays.copyOf(keys, size), snapshot);
        }


        private static String normalizeKey(String key) {
            Objects.requireNonNull(key, "attribute key");
            if (key.isBlank()) {
                throw new IllegalArgumentException("attribute key must not be blank");
            }
            return CaptureLimits.attributeKey(key);
        }

        private Builder putValue(String key, Object value, boolean alreadyCaptured) {
            for (int index = 0; index < size; index++) {
                if (keys[index].equals(key)) {
                    values[index] = value;
                    captured[index] = alreadyCaptured;
                    return this;
                }
            }
            if (size >= CaptureLimits.MAX_ATTRIBUTES - 1) {
                truncated = true;
                return this;
            }
            ensureCapacity(size + 1);
            keys[size] = key;
            values[size] = value;
            captured[size] = alreadyCaptured;
            size++;
            return this;
        }

        private void ensureCapacity(int needed) {
            if (needed <= keys.length) {
                return;
            }
            int next = Math.min(
                    CaptureLimits.MAX_ATTRIBUTES,
                    Math.max(needed, keys.length << 1));
            keys = Arrays.copyOf(keys, next);
            values = Arrays.copyOf(values, next);
            captured = Arrays.copyOf(captured, next);
        }

        private void putTruncationMarker() {
            String key = "logyard.attributes.truncated";
            for (int index = 0; index < size; index++) {
                if (keys[index].equals(key)) {
                    values[index] = true;
                    captured[index] = true;
                    return;
                }
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
}
