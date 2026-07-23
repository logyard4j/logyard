package com.zsumz.logyard.output.json.encoding;

import com.zsumz.logyard.api.event.AttributeSet;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Stable resource attributes attached to every JSON event. */
public final class ResourceAttributes {
    private final Map<String, Object> values;

    public ResourceAttributes(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        AttributeSet captured = AttributeSet.builder().putAll(values).build();
        this.values = Collections.unmodifiableMap(captured.toMap());
    }

    public Map<String, Object> values() {
        return values;
    }

    public static ResourceAttributes service(String name, String environment, String version) {
        return service(name, "", environment, version, "", Map.of());
    }

    public static ResourceAttributes service(
            String name,
            String namespace,
            String environment,
            String version,
            String instanceId,
            Map<String, String> custom) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("service.name", requireText(name, "name"));
        putIfText(values, "service.namespace", namespace);
        putIfText(values, "deployment.environment.name", environment);
        putIfText(values, "service.version", version);
        putIfText(values, "service.instance.id", instanceId);
        Objects.requireNonNull(custom, "custom").forEach(values::putIfAbsent);
        return new ResourceAttributes(values);
    }

    private static void putIfText(Map<String, Object> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }
}
