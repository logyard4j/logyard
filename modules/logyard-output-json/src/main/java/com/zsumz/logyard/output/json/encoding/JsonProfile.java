package com.zsumz.logyard.output.json.encoding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validated mapping from Logyard's canonical event fields to one JSON object profile. */
public final class JsonProfile {
    static final String TRUNCATED = "logyard.output.truncated";
    public static final Set<String> FIELDS = Set.of(
            "timestamp",
            "observed_timestamp_unix_nano",
            "severity_number",
            "severity_text",
            "logger",
            "event_name",
            "body",
            "message_template",
            "attributes",
            "resource",
            "thread",
            "exception");

    private static final Map<String, Map<String, String>> PRESETS = Map.of(
            "logyard", identity(),
            "ecs", mapping(
                    "timestamp", "@timestamp",
                    "observed_timestamp_unix_nano", "event.created",
                    "severity_number", "event.severity",
                    "severity_text", "log.level",
                    "logger", "log.logger",
                    "event_name", "event.action",
                    "body", "message",
                    "message_template", "logyard.message_template",
                    "attributes", "labels",
                    "resource", "service",
                    "thread", "process.thread",
                    "exception", "error"),
            "compact", mapping(
                    "timestamp", "ts",
                    "observed_timestamp_unix_nano", "observed_ns",
                    "severity_number", "sev",
                    "severity_text", "level",
                    "logger", "logger",
                    "event_name", "event",
                    "body", "msg",
                    "message_template", "template",
                    "attributes", "fields",
                    "resource", "resource",
                    "thread", "thread",
                    "exception", "error"));

    private final String name;
    private final boolean ecs;
    private final Map<String, String> outputNames;
    private final Set<String> dropped;
    private final JsonAttributeTransform attributes;

    private JsonProfile(
            String name,
            boolean ecs,
            Map<String, String> outputNames,
            Set<String> dropped,
            JsonAttributeTransform attributes) {
        this.name = name;
        this.ecs = ecs;
        this.outputNames = outputNames;
        this.dropped = dropped;
        this.attributes = attributes;
    }

    public static JsonProfile named(String name) {
        return custom(name, name, Map.of(), List.of(), JsonAttributeTransform.nested());
    }

    public static JsonProfile custom(
            String name,
            String preset,
            Map<String, String> rename,
            List<String> drop,
            JsonAttributeTransform attributes) {
        String normalizedName = component(name, "profile name");
        String normalizedPreset = Objects.requireNonNull(preset, "preset")
                .trim()
                .toLowerCase(Locale.ROOT);
        Map<String, String> base = PRESETS.get(normalizedPreset);
        if (base == null) {
            throw new IllegalArgumentException("JSON profile preset must be logyard, ecs, or compact");
        }
        LinkedHashMap<String, String> names = new LinkedHashMap<>(base);
        Objects.requireNonNullElse(rename, Map.<String, String>of()).forEach((field, target) -> {
            requireField(field);
            names.put(field, normalizeOutputName(target));
        });
        LinkedHashSet<String> dropped = new LinkedHashSet<>();
        List<String> droppedFields = drop == null ? List.of() : drop;
        for (String field : droppedFields) {
            requireField(field);
            if (!dropped.add(field)) {
                throw new IllegalArgumentException("duplicate dropped JSON field: " + field);
            }
        }
        if (dropped.contains("timestamp")) {
            throw new IllegalArgumentException("JSON profiles may not drop timestamp");
        }
        if (dropped.contains("body") && dropped.contains("event_name")) {
            throw new IllegalArgumentException("JSON profiles may not drop both body and event_name");
        }
        JsonAttributeTransform transform = Objects.requireNonNullElse(
                attributes, JsonAttributeTransform.nested());
        LinkedHashSet<String> destinations = new LinkedHashSet<>(Set.of(TRUNCATED));
        if ("ecs".equals(normalizedPreset)) {
            destinations.addAll(EcsProjection.RESERVED);
        }
        for (String field : FIELDS) {
            if (dropped.contains(field)
                    || ("attributes".equals(field)
                    && transform.mode() != JsonAttributeTransform.Mode.NESTED)) {
                continue;
            }
            String destination = names.get(field);
            if (!destinations.add(destination)) {
                throw new IllegalArgumentException(
                        "JSON profile output field collision at '" + destination + "'");
            }
        }
        if (transform.mode() == JsonAttributeTransform.Mode.FLATTEN) {
            for (String destination : destinations) {
                if (destination.startsWith(transform.prefix())) {
                    throw new IllegalArgumentException(
                            "flattened JSON attribute prefix collides with field '" + destination + "'");
                }
            }
        }
        return new JsonProfile(
                normalizedName,
                "ecs".equals(normalizedPreset),
                Collections.unmodifiableMap(names),
                Set.copyOf(dropped),
                transform);
    }

    public String name() {
        return name;
    }

    boolean ecs() {
        return ecs;
    }

    public boolean emits(String canonicalField) {
        requireField(canonicalField);
        return !dropped.contains(canonicalField);
    }

    public String outputName(String canonicalField) {
        requireField(canonicalField);
        return outputNames.get(canonicalField);
    }

    public JsonAttributeTransform attributes() {
        return attributes;
    }

    private static Map<String, String> identity() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String field : FIELDS) {
            result.put(field, field);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> mapping(String... entries) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(entries[index], entries[index + 1]);
        }
        return Collections.unmodifiableMap(result);
    }

    private static void requireField(String field) {
        if (!FIELDS.contains(field)) {
            throw new IllegalArgumentException("unknown canonical JSON field: " + field);
        }
    }

    private static String component(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (!normalized.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid JSON " + label + ": " + normalized);
        }
        return normalized;
    }

    private static String normalizeOutputName(String value) {
        String normalized = Objects.requireNonNull(value, "JSON output field").trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("JSON output field must be 1 to 128 characters");
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (Character.isISOControl(normalized.charAt(index))) {
                throw new IllegalArgumentException("JSON output field contains a control character");
            }
        }
        return normalized;
    }
}
