package com.logyard4j.logyard.api.event;

import com.logyard4j.logyard.api.annotation.InternalApi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Compact insertion-ordered immutable attributes with typed values. */
public final class AttributeSet {
    /** Shared empty attribute set. */
    public static final AttributeSet EMPTY = new AttributeSet(new String[0], null, new Object[0], false);

    final String[] keys;
    final String[] normalizedKeyIdentities;
    final Object[] values;
    final boolean captureTruncated;

    AttributeSet(String[] keys, String[] normalizedKeyIdentities, Object[] values, boolean captureTruncated) {
        this.keys = keys;
        this.normalizedKeyIdentities = normalizedKeyIdentities;
        this.values = values;
        this.captureTruncated = captureTruncated;
    }

    /** Returns the number of attributes.
     * @return number of attributes
     */
    public int size() { return keys.length; }
    /** Reports whether this set is empty.
     * @return whether this set is empty
     */
    public boolean isEmpty() { return keys.length == 0; }
    /** Returns an insertion-ordered key.
     * @param index zero-based attribute index
     * @return insertion-ordered key
     */
    public String keyAt(int index) { return keys[index]; }
    /** Returns an insertion-ordered captured value.
     * @param index zero-based attribute index
     * @return insertion-ordered captured value
     */
    public Object valueAt(int index) { return values[index]; }
    /** Finds a value by its exact captured key.
     * @param key exact captured key
     * @return captured value, or {@code null}
     */
    public Object get(String key) {
        for (int index = keys.length - 1; index >= 0; index--) {
            if (keys[index].equals(key)) {
                return values[index];
            }
        }
        return null;
    }
    /** Visits attributes in insertion order.
     * @param consumer receives attributes in insertion order
     */
    public void forEach(BiConsumer<String, Object> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (int index = 0; index < keys.length; index++) {
            consumer.accept(keys[index], values[index]);
        }
    }
    /** Creates a mutable insertion-ordered copy.
     * @return mutable insertion-ordered copy
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        forEach(result::put);
        return result;
    }

    AttributeSet withSystemAttribute(String key, Object value) {
        return AttributeSetOperations.withSystemAttribute(this, key, value);
    }

    /** Merges attributes with right-hand precedence.
     * @param other attributes to merge right-biased
     * @return merged immutable attributes
     */
    public AttributeSet mergedWith(AttributeSet other) {
        Objects.requireNonNull(other, "other");
        if (other.isEmpty() && !other.captureTruncated) {
            return this;
        }
        if (isEmpty() && !captureTruncated) {
            return other;
        }
        return builder(size() + other.size()).putAll(this).putAll(other).build();
    }

    /** Creates a builder with typical event capacity.
     * @return builder with typical event capacity
     */
    public static Builder builder() { return new Builder(8, false); }
    /** Creates a builder sized for an expected attribute count.
     * @param expectedSize expected attribute count
     * @return bounded builder
     */
    public static Builder builder(int expectedSize) { return new Builder(expectedSize, false); }
    /** Creates a builder permitted to write Logyard-owned diagnostic attributes.
     * @return trusted bounded builder
     * @hidden
     */
    @InternalApi
    public static Builder systemBuilder() { return new Builder(8, true); }
    /** Creates a trusted builder sized for an expected attribute count.
     * @param expectedSize expected attribute count
     * @return trusted bounded builder
     * @hidden
     */
    @InternalApi
    public static Builder systemBuilder(int expectedSize) { return new Builder(expectedSize, true); }
    /** Creates an immutable one-entry set.
     * @param key attribute key
     * @param value attribute value
     * @return one-entry immutable set
     */
    public static AttributeSet of(String key, Object value) { return builder(1).put(key, value).build(); }
    /** Tests whether a key is in Logyard's reserved namespace.
     * @param key attribute key
     * @return whether the key is in the case-insensitive {@code logyard.*} namespace
     */
    public static boolean isReservedKey(String key) {
        return key != null && key.regionMatches(true, 0, "logyard.", 0, "logyard.".length());
    }

    /** Mutable, bounded assembler for an immutable {@link AttributeSet}. */
    public static final class Builder {
        final AttributeSetBuilderState state;

        private Builder(int expectedSize, boolean systemAttributesAllowed) {
            state = new AttributeSetBuilderState(expectedSize, systemAttributesAllowed);
        }

        /** Adds or replaces an attribute.
         * @param key attribute key
         * @param value attribute value
         * @return this builder
         */
        public Builder put(String key, Object value) {
            state.put(key, value);
            return this;
        }
        /** Evaluates and captures an attribute supplier.
         * @param key attribute key
         * @param supplier value supplier evaluated during capture
         * @return this builder
         */
        public Builder putSupplied(String key, Supplier<?> supplier) {
            state.putSupplied(key, supplier);
            return this;
        }
        /** Returns the current attribute count.
         * @return current attribute count
         */
        public int size() { return state.size(); }
        /** Reports whether no additional user attribute fits without truncation.
         * @return whether no additional user attribute fits without truncation
         */
        public boolean isFull() { return state.isFull(); }
        /** Records omitted source attributes.
         * @return this builder
         */
        public Builder markTruncated() {
            state.markTruncated();
            return this;
        }
        /** Preserves event-level capture shortening.
         * @return this builder
         * @hidden
         */
        @InternalApi
        public Builder markCaptureTruncated() {
            state.markCaptureTruncated();
            return this;
        }
        /** Adds every attribute from an immutable set.
         * @param attributes immutable attributes to add
         * @return this builder
         */
        public Builder putAll(AttributeSet attributes) {
            state.putAll(Objects.requireNonNull(attributes, "attributes"));
            return this;
        }
        /** Adds map entries in iteration order.
         * @param attributes map entries to add in iteration order
         * @return this builder
         */
        public Builder putAll(Map<String, ?> attributes) {
            state.putAll(Objects.requireNonNull(attributes, "attributes"));
            return this;
        }
        /** Captures values into an immutable attribute set.
         * @return immutable captured attributes
         */
        public AttributeSet build() { return state.build(); }
        /** Adds a key that passed Logyard's bounded normalization policy.
         * @param key key that passed Logyard's bounded normalization policy
         * @param value attribute value
         * @return this builder
         * @hidden
         */
        @InternalApi
        public Builder putNormalized(NormalizedAttributeKey key, Object value) {
            state.putNormalized(key, value);
            return this;
        }
    }

    AttributeSet recapture(CaptureContext context) { return AttributeSetOperations.recapture(this, context); }
    boolean captureTruncated() { return captureTruncated; }
}
