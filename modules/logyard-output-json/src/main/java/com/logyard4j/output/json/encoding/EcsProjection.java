package com.logyard4j.output.json.encoding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** ECS 9.4 service projection; non-ECS resource keys stay in a separate Logyard field. */
final class EcsProjection {
    static final String VERSION = "9.4.0";
    static final Set<String> RESERVED = Set.of("ecs.version", "logyard.resource", "trace.id", "span.id", "logyard.trace_flags");
    private static final Map<String, String> SERVICE = Map.of(
            "service.name", "name",
            "service.version", "version",
            "deployment.environment.name", "environment");
    private final Map<String, Object> service;
    private final Map<String, Object> extra;

    EcsProjection(ResourceAttributes resource) {
        Map<String, Object> projected = new LinkedHashMap<>();
        Map<String, Object> remaining = new LinkedHashMap<>();
        resource.values().forEach((key, value) -> {
            String destination = SERVICE.get(key);
            if (destination != null) {
                if (value != null) {
                    projected.put(destination, text(value));
                }
            } else if ("service.instance.id".equals(key)) {
                if (value != null) {
                    projected.put("node", Map.of("name", text(value)));
                }
            } else {
                remaining.put(key, value);
            }
        });
        service = Collections.unmodifiableMap(projected);
        extra = Collections.unmodifiableMap(remaining);
    }

    void resource(JsonWriter json) {
        json.value(service);
        if (!extra.isEmpty()) {
            json.comma();
            json.name("logyard.resource");
            json.value(extra);
        }
    }

    static String traceField(String name, Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        return switch (name) {
            case "trace_id" -> "trace.id";
            case "span_id" -> "span.id";
            case "trace_flags" -> "logyard.trace_flags";
            default -> null;
        };
    }

    private static String text(Object value) {
        if (value instanceof String text) {
            return text;
        }
        JsonWriter writer = new JsonWriter(128);
        writer.value(value);
        return writer.result();
    }
}
