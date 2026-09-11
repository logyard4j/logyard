package com.logyard4j.core.runtime.publication;

import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.AttributeKey;
import com.logyard4j.api.event.CaptureLimits;
import com.logyard4j.api.event.CapturedAttributeAccess;
import com.logyard4j.api.event.NormalizedAttributeKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Bounded attribute declarations retained until an enabled event is captured. */
final class PendingAttributes {
    private final List<Entry> values = new ArrayList<>(4);
    private boolean truncated;
    private boolean captureTruncated;

    void add(String key, Object value) {
        add(new Entry(AttributeKey.normalize(key, false), PendingValue.direct(value), null, 0));
    }

    void add(String key, Supplier<?> supplier) {
        add(new Entry(AttributeKey.normalize(key, false), PendingValue.supplied(supplier), null, 0));
    }

    void addAll(AttributeSet source) {
        Objects.requireNonNull(source, "values");
        captureTruncated |= CapturedAttributeAccess.truncated(source);
        for (int index = 0; index < source.size(); index++) {
            String key = source.keyAt(index);
            add(new Entry(new NormalizedAttributeKey(key, key, false), null, source, index));
        }
    }

    AttributeSet capture(AttributeSet scopedContext) {
        AttributeSet.Builder captured = AttributeSet.builder(scopedContext.size() + values.size());
        captured.putAll(scopedContext);
        for (Entry entry : values) {
            if (entry.source != null) {
                CapturedAttributeAccess.copyEntry(captured, entry.source, entry.index);
            } else {
                // The builder replaces existing keys even when full, without evaluating dropped suppliers.
                captured.putSupplied(entry.key.original(), entry.value::resolve);
            }
        }
        if (truncated) captured.markTruncated();
        if (captureTruncated) captured.markCaptureTruncated();
        return captured.build();
    }

    private void add(Entry value) {
        for (int index = 0; index < values.size(); index++) {
            Entry existing = values.get(index);
            if (!existing.normalizedCapture() && !value.normalizedCapture()
                    && existing.key.storageKey().equals(value.key.storageKey())
                    && existing.key.original().equals(value.key.original())) {
                values.set(index, value);
                return;
            }
        }
        if (values.size() >= CaptureLimits.MAX_ATTRIBUTES - 1) {
            truncated = true;
            return;
        }
        values.add(value);
    }

    void clear() {
        values.clear();
        truncated = false;
        captureTruncated = false;
    }

    private record Entry(NormalizedAttributeKey key, PendingValue value, AttributeSet source, int index) {
        boolean normalizedCapture() {
            return source != null && CapturedAttributeAccess.normalizedKey(source, index);
        }
    }
}
