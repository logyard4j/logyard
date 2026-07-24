package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.AttributeKey;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.NormalizedAttributeKey;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Validated attribute declarations whose caller-owned values are captured at publication. */
final class PendingAttributes {
    private final List<Entry> values = new ArrayList<>(4);
    private boolean truncated;

    void add(String key, Object value) {
        add(requireKey(key), PendingValue.direct(value));
    }

    void add(String key, Supplier<?> supplier) {
        add(requireKey(key), PendingValue.supplied(supplier));
    }

    AttributeSet capture() {
        AttributeSet.Builder captured = AttributeSet.builder(values.size());
        for (Entry entry : values) {
            if (captured.isFull()) {
                captured.markTruncated();
                break;
            }
            captured.putNormalized(entry.key, entry.value.resolve());
        }
        if (truncated) {
            captured.markTruncated();
        }
        return captured.build();
    }

    private void add(NormalizedAttributeKey key, PendingValue value) {
        for (Entry entry : values) {
            if (entry.key.storageKey().equals(key.storageKey())
                    && entry.key.original().equals(key.original())) {
                entry.value = value;
                return;
            }
        }
        if (values.size() >= CaptureLimits.MAX_ATTRIBUTES - 1) {
            truncated = true;
            return;
        }
        values.add(new Entry(key, value));
    }

    void clear() {
        values.clear();
        truncated = false;
    }

    private static NormalizedAttributeKey requireKey(String key) {
        return AttributeKey.normalize(key, false);
    }

    private static final class Entry {
        private final NormalizedAttributeKey key;
        private PendingValue value;

        private Entry(NormalizedAttributeKey key, PendingValue value) {
            this.key = key;
            this.value = value;
        }
    }
}
