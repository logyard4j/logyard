package com.logyard4j.logyard.slf4j.internal.event;

import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.CaptureLimits;

import java.util.Objects;

/** Defers mutable attribute storage until an enabled event contributes metadata. */
final class DeferredAttributes {
    private final AttributeSet initial;
    private AttributeSet.Builder builder;
    private AttributeSet.Builder systemBuilder;

    DeferredAttributes(AttributeSet initial) {
        this.initial = Objects.requireNonNull(initial, "initial");
    }

    void put(String key, Object value) {
        if (AttributeSet.isReservedKey(key)) {
            return;
        }
        builder().put(key, value);
    }

    void putSystem(String key, Object value) {
        if (systemBuilder == null) {
            systemBuilder = AttributeSet.systemBuilder(4);
        }
        systemBuilder.put(key, value);
    }

    boolean isFull() {
        return builder == null
                ? initial.size() >= CaptureLimits.MAX_ATTRIBUTES - 1
                : builder.isFull();
    }

    AttributeSet build() {
        AttributeSet userAttributes = builder == null ? initial : builder.build();
        return systemBuilder == null ? userAttributes : userAttributes.mergedWith(systemBuilder.build());
    }

    private AttributeSet.Builder builder() {
        if (builder == null) {
            builder = AttributeSet.builder(initial.size() + 8).putAll(initial);
        }
        return builder;
    }
}
