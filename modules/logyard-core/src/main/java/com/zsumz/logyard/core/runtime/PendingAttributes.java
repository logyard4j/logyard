package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Validated attribute declarations whose caller-owned values are captured at publication. */
final class PendingAttributes {
    private final Map<String, PendingValue> values = new LinkedHashMap<>();
    private boolean truncated;

    void add(String key, Object value) {
        add(requireKey(key), PendingValue.direct(value));
    }

    void add(String key, Supplier<?> supplier) {
        add(requireKey(key), PendingValue.supplied(supplier));
    }

    AttributeSet capture(int omittedArguments) {
        AttributeSet.Builder captured = AttributeSet.builder(values.size() + (omittedArguments > 0 ? 1 : 0));
        if (omittedArguments > 0) {
            captured.put("logyard.arguments.omitted", omittedArguments);
        }
        for (Map.Entry<String, PendingValue> entry : values.entrySet()) {
            if (captured.isFull()) {
                captured.markTruncated();
                break;
            }
            captured.put(entry.getKey(), entry.getValue().resolve());
        }
        if (truncated) {
            captured.markTruncated();
        }
        return captured.build();
    }

    private void add(String key, PendingValue value) {
        if (!values.containsKey(key) && values.size() >= CaptureLimits.MAX_ATTRIBUTES - 1) {
            truncated = true;
            return;
        }
        values.put(key, value);
    }

    private static String requireKey(String key) {
        String value = Objects.requireNonNull(key, "attribute key");
        if (value.isBlank()) {
            throw new IllegalArgumentException("attribute key must not be blank");
        }
        return CaptureLimits.attributeKey(value);
    }
}
