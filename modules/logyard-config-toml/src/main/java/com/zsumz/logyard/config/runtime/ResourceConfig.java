package com.zsumz.logyard.config.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded resource attributes shared by structured outputs. */
public record ResourceConfig(Map<String, String> attributes) {
    public static final int MAX_ATTRIBUTES = 64;
    public static final int MAX_KEY_LENGTH = 128;
    public static final int MAX_VALUE_LENGTH = 4_096;
    private static final Set<String> RESERVED = Set.of(
            "service.name",
            "service.namespace",
            "service.version",
            "service.instance.id",
            "deployment.environment.name");

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
    }
}
