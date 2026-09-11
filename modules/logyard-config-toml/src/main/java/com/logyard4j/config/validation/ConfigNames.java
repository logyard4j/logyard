package com.logyard4j.config.validation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Central normalization policy for configured component names and references. */
public final class ConfigNames {
    private static final String COMPONENT_PATTERN = "[A-Za-z][A-Za-z0-9_.-]{0,63}";
    private static final String PROVIDER_PATTERN = "[a-z][a-z0-9_.-]{0,63}";

    private ConfigNames() {
    }

    /** Returns one validated component name. */
    public static String component(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (!normalized.matches(COMPONENT_PATTERN)) {
            throw new IllegalArgumentException("invalid " + label + ": " + normalized);
        }
        return normalized;
    }

    /** Returns one normalized lower-case provider name. */
    public static String provider(String value) {
        String normalized = Objects.requireNonNull(value, "provider").trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(PROVIDER_PATTERN)) {
            throw new IllegalArgumentException("invalid provider name: " + normalized);
        }
        return normalized;
    }

    /** Returns a validated optional component reference. */
    public static String optionalReference(String value, String label) {
        return value == null ? null : component(value, label);
    }

    /** Returns a bounded, immutable list of unique component references. */
    public static List<String> uniqueReferences(List<String> values, String label, boolean nullable) {
        if (values == null && nullable) {
            return null;
        }
        List<String> supplied = values == null ? List.of() : values;
        if (supplied.size() > 128) {
            throw new IllegalArgumentException(label + " must contain at most 128 entries");
        }
        List<String> copy = new ArrayList<>(supplied.size());
        for (String value : supplied) {
            copy.add(component(value, label + " entry"));
        }
        if (new LinkedHashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(label + " contains duplicate entries");
        }
        return List.copyOf(copy);
    }
}
