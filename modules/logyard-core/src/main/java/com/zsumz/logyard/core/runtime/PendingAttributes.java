package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.AttributeKey;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.NormalizedAttributeKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Validated attribute declarations whose caller-owned values are captured at publication. */
final class PendingAttributes {
    private final Map<NormalizedAttributeKey, PendingValue> values = new LinkedHashMap<>();
    private boolean truncated;

    void add(String key, Object value) {
        add(requireKey(key), PendingValue.direct(value));
    }

    void add(String key, Supplier<?> supplier) {
        add(requireKey(key), PendingValue.supplied(supplier));
    }

    AttributeSet capture() {
        AttributeSet.Builder captured = AttributeSet.builder(values.size());
        for (Map.Entry<NormalizedAttributeKey, PendingValue> entry : values.entrySet()) {
            if (captured.isFull()) {
                captured.markTruncated();
                break;
            }
            captured.putNormalized(entry.getKey(), entry.getValue().resolve());
        }
        if (truncated) {
            captured.markTruncated();
        }
        return captured.build();
    }

    private void add(NormalizedAttributeKey key, PendingValue value) {
        if (!values.containsKey(key) && values.size() >= CaptureLimits.MAX_ATTRIBUTES - 1) {
            truncated = true;
            return;
        }
        values.put(key, value);
    }

    private static NormalizedAttributeKey requireKey(String key) {
        return AttributeKey.normalize(key, false);
    }
}
