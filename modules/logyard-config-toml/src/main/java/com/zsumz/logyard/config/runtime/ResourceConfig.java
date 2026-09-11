package com.zsumz.logyard.config.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded resource attributes shared by structured outputs. */
public record ResourceConfig(Map<String, String> attributes, List<String> include, List<String> exclude) {
    public static final int MAX_ATTRIBUTES = 64;
    public static final int MAX_KEY_LENGTH = 128;
    public static final int MAX_VALUE_LENGTH = 4_096;
    private static final Set<String> RESERVED = Set.of(
            "service.name",
            "service.namespace",
            "service.version",
            "service.instance.id",
            "deployment.environment.name");

    public ResourceConfig(Map<String, String> attributes) {
        this(attributes, List.of(), List.of());
    }

    public ResourceConfig {
        Objects.requireNonNull(attributes, "attributes");
        if (attributes.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException(
                    "resource attributes must contain at most " + MAX_ATTRIBUTES + " entries");
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            String normalizedKey = Objects.requireNonNull(key, "resource attribute key").trim();
            String normalizedValue = Objects.requireNonNull(value, "resource attribute value");
            if (normalizedKey.isEmpty() || normalizedKey.length() > MAX_KEY_LENGTH
                    || !normalizedKey.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
                throw new IllegalArgumentException("invalid resource attribute key: " + normalizedKey);
            }
            if (RESERVED.contains(normalizedKey)) {
                throw new IllegalArgumentException(
                        "resource attribute is managed by [service]: " + normalizedKey);
            }
            if (normalizedValue.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "resource attribute value exceeds " + MAX_VALUE_LENGTH + " characters: "
                                + normalizedKey);
            }
            copy.put(normalizedKey, normalizedValue);
        });
        attributes = Collections.unmodifiableMap(copy);
        include = selection(include, "include");
        exclude = selection(exclude, "exclude");
    }

    /** Selects canonical resource keys before capture and schema projection; exclusions always win. */
    public boolean includes(String key) {
        return (include.isEmpty() || include.contains(key)) && !exclude.contains(key);
    }

    private static List<String> selection(List<String> keys, String label) {
        List<String> copy = List.copyOf(keys);
        if (copy.size() > MAX_ATTRIBUTES || Set.copyOf(copy).size() != copy.size()) {
            throw new IllegalArgumentException("resource " + label + " requires at most 64 unique keys");
        }
        for (String key : copy) {
            if (key.length() > MAX_KEY_LENGTH || !key.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
                throw new IllegalArgumentException("invalid resource " + label + " key: " + key);
            }
        }
        return copy;
    }
}
