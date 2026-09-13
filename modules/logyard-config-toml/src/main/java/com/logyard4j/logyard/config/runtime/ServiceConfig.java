package com.logyard4j.logyard.config.runtime;

import java.util.Objects;

/** Enterprise service identity mapped to structured resource attributes. */
public record ServiceConfig(
        String name,
        String namespace,
        String version,
        String environment,
        String instanceId) {
    public ServiceConfig {
        name = requireText(name, "name");
        namespace = normalize(namespace);
        version = normalize(version);
        environment = normalize(environment);
        instanceId = normalize(instanceId);
    }

    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "service attribute").trim();
    }
}
