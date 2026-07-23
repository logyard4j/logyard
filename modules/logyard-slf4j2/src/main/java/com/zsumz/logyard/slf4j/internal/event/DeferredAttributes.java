package com.zsumz.logyard.slf4j.internal.event;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;

import java.util.Objects;

/** Defers mutable attribute storage until an enabled event contributes metadata. */
final class DeferredAttributes {
    private final AttributeSet initial;
    private AttributeSet.Builder builder;

    DeferredAttributes(AttributeSet initial) {
        this.initial = Objects.requireNonNull(initial, "initial");
    }

    void put(String key, Object value) {
        builder().put(key, value);
    }

    boolean isFull() {
        return builder == null
                ? initial.size() >= CaptureLimits.MAX_ATTRIBUTES - 1
                : builder.isFull();
    }

    AttributeSet build() {
        return builder == null ? initial : builder.build();
    }

    private AttributeSet.Builder builder() {
        if (builder == null) {
            builder = AttributeSet.builder(initial.size() + 8).putAll(initial);
        }
        return builder;
    }
}
