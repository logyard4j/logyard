package com.logyard4j.logyard.runtime.context;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable adapter-context allowlist prepared on the configuration control plane. */
public final class ContextPolicySnapshot {
    private static final ContextPolicySnapshot NONE = new ContextPolicySnapshot(Set.of());
    private static final ContextPolicySnapshot ALL = new ContextPolicySnapshot(Set.of("*"));

    private final Set<String> includedKeys;
    private final boolean includesAll;

    private ContextPolicySnapshot(Set<String> includedKeys) {
        this.includedKeys = includedKeys;
        includesAll = includedKeys.contains("*");
    }

    public static ContextPolicySnapshot none() {
        return NONE;
    }

    public static ContextPolicySnapshot all() {
        return ALL;
    }

    public static ContextPolicySnapshot of(List<String> includedKeys) {
        Objects.requireNonNull(includedKeys, "includedKeys");
        if (includedKeys.isEmpty()) {
            return NONE;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String key : includedKeys) {
            Objects.requireNonNull(key, "included context key");
            if (key.isBlank()) {
                throw new IllegalArgumentException("included context key must not be blank");
            }
            normalized.add(key);
        }
        return new ContextPolicySnapshot(Collections.unmodifiableSet(normalized));
    }

    public Set<String> includedKeys() {
        return includedKeys;
    }

    public boolean includesAll() {
        return includesAll;
    }

    public boolean disabled() {
        return !includesAll && includedKeys.isEmpty();
    }

    public boolean includes(String key) {
        return includesAll || includedKeys.contains(key);
    }
}
