package com.zsumz.logyard.slf4j.internal.context;

import com.zsumz.logyard.api.event.AttributeSet;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Allowlist strategy that atomically follows the runtime's current configuration. */
public final class ContextSnapshotPolicy {
    private final Supplier<List<String>> includedKeysSource;
    private volatile Snapshot snapshot;

    public ContextSnapshotPolicy(List<String> includedKeys) {
        List<String> fixed = List.copyOf(Objects.requireNonNull(includedKeys, "includedKeys"));
        includedKeysSource = () -> fixed;
        snapshot = normalize(fixed);
    }

    public ContextSnapshotPolicy(Supplier<List<String>> includedKeysSource) {
        this.includedKeysSource = Objects.requireNonNull(includedKeysSource, "includedKeysSource");
        snapshot = normalize(currentSource());
    }

    public AttributeSet capture(LogyardMdcAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        Snapshot current = currentSnapshot();
        if (!current.includeAll() && current.includedKeys().isEmpty()) {
            return AttributeSet.EMPTY;
        }
        Map<String, String> values = adapter.currentValues();
        if (values.isEmpty()) {
            return AttributeSet.EMPTY;
        }
        AttributeSet.Builder attributes = AttributeSet.builder(
                current.includeAll()
                        ? values.size()
                        : Math.min(values.size(), current.includedKeys().size()));
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (current.includeAll() || current.includedKeys().contains(entry.getKey())) {
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
        return currentSnapshot().includeAll();
    }

    private Snapshot currentSnapshot() {
        List<String> currentSource = currentSource();
        Snapshot current = snapshot;
        if (current.source().equals(currentSource)) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            if (!current.source().equals(currentSource)) {
                current = normalize(currentSource);
                snapshot = current;
            }
            return current;
        }
    }

    private List<String> currentSource() {
        return List.copyOf(Objects.requireNonNull(includedKeysSource.get(), "included context keys"));
    }

    private static Snapshot normalize(List<String> includedKeys) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String key : includedKeys) {
            Objects.requireNonNull(key, "included context key");
            if (key.isBlank()) {
                throw new IllegalArgumentException("included context key must not be blank");
            }
            normalized.add(key);
        }
        Set<String> immutable = Collections.unmodifiableSet(new LinkedHashSet<>(normalized));
        return new Snapshot(List.copyOf(includedKeys), immutable, immutable.contains("*"));
    }

    private record Snapshot(List<String> source, Set<String> includedKeys, boolean includeAll) {
    }
}
