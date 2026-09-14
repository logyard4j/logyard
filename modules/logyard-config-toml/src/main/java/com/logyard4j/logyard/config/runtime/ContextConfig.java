package com.logyard4j.logyard.config.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Explicit capture policy for trace identity, local MDC, baggage, and redaction. */
public record ContextConfig(
        boolean trace,
        List<String> mdc,
        List<String> baggage,
        List<String> redact) {
    public static final int MAX_ALLOWLIST_ENTRIES = 128;

    public ContextConfig {
        mdc = boundedUnique(mdc, "mdc");
        baggage = boundedUnique(baggage, "baggage");
        redact = boundedUnique(redact, "redact");
    }

    /** Provider-neutral capture keys used by the context SPI. */
    public List<String> providerKeys() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (trace) {
            result.add("trace.*");
        }
        for (String key : baggage) {
            result.add("baggage." + key);
        }
        return List.copyOf(result);
    }

    private static List<String> boundedUnique(List<String> values, String label) {
        Objects.requireNonNull(values, label);
        if (values.size() > MAX_ALLOWLIST_ENTRIES) {
            throw new IllegalArgumentException(
                    label + " must contain at most " + MAX_ALLOWLIST_ENTRIES + " entries");
        }
        List<String> copy = List.copyOf(values);
        Set<String> unique = new LinkedHashSet<>(copy);
        if (unique.size() != copy.size()) {
            throw new IllegalArgumentException(label + " contains duplicate entries");
        }
        for (String value : copy) {
            if (value.isBlank() || value.length() > 128) {
                throw new IllegalArgumentException(label + " entries must be 1 to 128 characters");
            }
        }
        return copy;
    }
}
