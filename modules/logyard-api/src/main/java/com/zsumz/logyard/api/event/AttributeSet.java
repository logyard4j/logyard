package com.zsumz.logyard.api.event;

import com.zsumz.logyard.api.annotation.InternalApi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Compact insertion-ordered immutable attributes with typed values. */
public final class AttributeSet {
    /** Shared empty attribute set. */
    public static final AttributeSet EMPTY = new AttributeSet(new String[0], new Object[0]);

    final String[] keys;
    final Object[] values;

    AttributeSet(String[] keys, Object[] values) {
        this.keys = keys;
        this.values = values;
    }

    /**
     * Returns the number of attributes.
     *
     * @return attribute count
     */
    public int size() { return keys.length; }

    /**
     * Returns whether this set contains no attributes.
     *
     * @return {@code true} when empty
     */
    public boolean isEmpty() { return keys.length == 0; }

    /**
     * Returns the key at an insertion-order index.
     *
     * @param index zero-based attribute index
     * @return attribute key
     */
    public String keyAt(int index) { return keys[index]; }

    /**
     * Returns the value at an insertion-order index.
     *
     * @param index zero-based attribute index
     * @return captured attribute value
     */
    public Object valueAt(int index) { return values[index]; }

    /**
     * Returns the value associated with a key.
     *
     * @param key attribute key
     * @return captured value, or {@code null} when the key is absent
     */
    public Object get(String key) {
        for (int index = keys.length - 1; index >= 0; index--) {
            if (keys[index].equals(key)) {
                return values[index];
            }
        }
        return null;
    }

    /**
     * Visits attributes in insertion order.
     *
     * @param consumer attribute consumer
     */
    public void forEach(BiConsumer<String, Object> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (int index = 0; index < keys.length; index++) {
            consumer.accept(keys[index], values[index]);
        }
    }

    /**
     * Returns a mutable insertion-ordered copy of these attributes.
     *
     * @return mutable attribute map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        forEach(result::put);
        return result;
    }

    AttributeSet withSystemAttribute(String key, Object value) {
        return AttributeSetOperations.withSystemAttribute(this, key, value);
    }

    /**
     * Returns the right-biased merge of this set and another set.
     *
     * @param other attributes to append or replace
     * @return immutable merged attributes
     */
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

    /**
     * Returns a builder sized for a typical event.
     *
     * @return new bounded builder
     */
    public static Builder builder() { return new Builder(8, false); }

    /**
     * Returns a builder sized for an expected number of attributes.
     *
     * @param expectedSize expected attribute count
     * @return new bounded builder
     */
    public static Builder builder(int expectedSize) { return new Builder(expectedSize, false); }

    /**
     * Returns a builder authorized to emit Logyard-owned diagnostic attributes.
     *
     * <p>This is an implementation contract for Logyard modules. Applications must use
     * {@link #builder()} and cannot write the reserved {@code logyard.*} namespace.</p>
     *
     * @return trusted system-attribute builder
     */
    @InternalApi
    public static Builder systemBuilder() { return new Builder(8, true); }

    /**
     * Returns a sized builder authorized to emit Logyard-owned diagnostic attributes.
     *
     * @param expectedSize expected attribute count
     * @return trusted system-attribute builder
     */
    @InternalApi
    public static Builder systemBuilder(int expectedSize) { return new Builder(expectedSize, true); }

    /**
     * Creates a set containing one attribute.
     *
     * @param key attribute key
     * @param value attribute value
     * @return one-entry immutable set
     */
    public static AttributeSet of(String key, Object value) { return builder(1).put(key, value).build(); }

    /**
     * Returns whether a key belongs to Logyard's reserved system-diagnostic namespace.
     *
     * @param key attribute key
     * @return {@code true} for a case-insensitive {@code logyard.*} prefix
     */
    public static boolean isReservedKey(String key) {
        return key != null && key.regionMatches(true, 0, "logyard.", 0, "logyard.".length());
    }

    /** Mutable, bounded assembler for an immutable {@link AttributeSet}. */
    public static final class Builder {
        private final AttributeAccumulator attributes;
        private final boolean systemAttributesAllowed;

        private Builder(int expectedSize, boolean systemAttributesAllowed) {
            attributes = new AttributeAccumulator(expectedSize);
            this.systemAttributesAllowed = systemAttributesAllowed;
        }

        /**
         * Adds or replaces an attribute.
         *
         * @param key attribute key
         * @param value attribute value
         * @return this builder
         */
        public Builder put(String key, Object value) {
            attributes.put(normalizeKey(key), value);
            return this;
        }

        /**
         * Evaluates and captures a value supplier while building an enabled event.
         * The supplier is never retained by the resulting immutable attribute set.
         *
         * @param key attribute key
         * @param supplier attribute supplier
         * @return this builder
         */
        public Builder putSupplied(String key, Supplier<?> supplier) {
            String normalized = normalizeKey(key);
            Objects.requireNonNull(supplier, "valueSupplier");
            attributes.putSupplied(normalized, supplier);
            return this;
        }

        /**
         * Returns the number of attributes currently held by the builder.
         *
         * @return attribute count
         */
        public int size() {
            return attributes.size();
        }

        /**
         * Returns whether no further user attributes can be accepted without truncation.
         *
         * @return {@code true} when full
         */
        public boolean isFull() {
            return attributes.isFull();
        }

        /**
         * Records that a source contained additional attributes which could not be captured.
         *
         * @return this builder
         */
        public Builder markTruncated() {
            attributes.markTruncated();
            return this;
        }

        /**
         * Adds all attributes from an immutable set.
         *
         * @param attributes attributes to add
         * @return this builder
         */
        public Builder putAll(AttributeSet attributes) {
            Objects.requireNonNull(attributes, "attributes");
            for (int index = 0; index < attributes.size(); index++) {
                this.attributes.put(attributes.keyAt(index), attributes.valueAt(index));
            }
            return this;
        }

        /**
         * Adds all entries from a map in iteration order.
         *
         * @param attributes attributes to add
         * @return this builder
         */
        public Builder putAll(Map<String, ?> attributes) {
            Objects.requireNonNull(attributes, "attributes");
            for (Map.Entry<String, ?> entry : attributes.entrySet()) {
                put(entry.getKey(), entry.getValue());
                if (this.attributes.truncated() && isFull()) {
                    break;
                }
            }
            return this;
        }

        /**
         * Captures all values and returns an immutable attribute set.
         *
         * @return immutable captured attributes
         */
        public AttributeSet build() {
            return attributes.build();
        }

        private String normalizeKey(String key) {
            return normalizeKey(key, systemAttributesAllowed);
        }

        private static String normalizeKey(String key, boolean systemAttributesAllowed) {
            Objects.requireNonNull(key, "attribute key");
            if (key.isBlank()) {
                throw new IllegalArgumentException("attribute key must not be blank");
            }
            if (!systemAttributesAllowed && isReservedKey(key)) {
                throw new IllegalArgumentException("attribute keys in the logyard.* namespace are reserved");
            }
            return CaptureLimits.attributeKey(key);
        }
    }

    AttributeSet recapture(CaptureContext context) {
        return AttributeSetOperations.recapture(this, context);
    }

}
