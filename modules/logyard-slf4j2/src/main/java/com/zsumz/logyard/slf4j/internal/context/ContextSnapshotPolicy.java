package com.zsumz.logyard.slf4j.internal.context;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.runtime.context.ContextPolicySnapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Allowlist strategy backed by a control-plane-published immutable snapshot. */
public final class ContextSnapshotPolicy {
    private final Supplier<ContextPolicySnapshot> snapshotSource;

    public ContextSnapshotPolicy(List<String> includedKeys) {
        ContextPolicySnapshot fixed = ContextPolicySnapshot.of(includedKeys);
        snapshotSource = () -> fixed;
    }

    public ContextSnapshotPolicy(Supplier<ContextPolicySnapshot> snapshotSource) {
        this.snapshotSource = Objects.requireNonNull(snapshotSource, "snapshotSource");
    }

    public AttributeSet capture(LogyardMdcAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        ContextPolicySnapshot current = currentSnapshot();
        if (current.disabled()) {
            return AttributeSet.EMPTY;
        }
        Map<String, String> values = adapter.currentValues();
        if (values.isEmpty()) {
            return AttributeSet.EMPTY;
        }
        AttributeSet.Builder attributes = AttributeSet.builder(
                current.includesAll()
                        ? values.size()
                        : Math.min(values.size(), current.includedKeys().size()));
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (current.includes(entry.getKey())) {
                attributes.put(entry.getKey(), entry.getValue());
                if (attributes.isFull()) {
                    break;
                }
            }
        }
        return attributes.build();
    }

    public Set<String> includedKeys() {
        return currentSnapshot().includedKeys();
    }

    public boolean includesAll() {
        return currentSnapshot().includesAll();
    }

    private ContextPolicySnapshot currentSnapshot() {
        return Objects.requireNonNull(snapshotSource.get(), "context policy snapshot");
    }
}
