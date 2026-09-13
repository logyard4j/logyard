package com.logyard4j.logyard.config.encoding;

import com.logyard4j.logyard.config.validation.ConfigNames;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Named JSON field profile with exact field renames, drops, and attribute transforms. */
public record JsonProfileConfig(
        String name,
        String preset,
        Map<String, String> rename,
        List<String> drop,
        JsonAttributeTransformConfig attributes) {
    public static final Set<String> PRESETS = Set.of("logyard", "ecs", "compact");
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

    public JsonProfileConfig {
        name = ConfigNames.component(name, "JSON profile name");
        if (PRESETS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "custom JSON profile name must not shadow built-in profile '" + name + "'");
        }
        preset = Objects.requireNonNullElse(preset, "logyard").trim().toLowerCase(Locale.ROOT);
        if (!PRESETS.contains(preset)) {
            throw new IllegalArgumentException("JSON profile preset must be logyard, ecs, or compact");
        }
        LinkedHashMap<String, String> renamed = new LinkedHashMap<>();
        Objects.requireNonNullElse(rename, Map.<String, String>of()).forEach((field, target) -> {
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("unknown JSON field transform source: " + field);
            }
            String normalized = outputName(target);
            renamed.put(field, normalized);
        });
        if (new LinkedHashSet<>(renamed.values()).size() != renamed.size()) {
            throw new IllegalArgumentException("JSON field transform destinations must be unique");
        }
        rename = Collections.unmodifiableMap(renamed);
        drop = List.copyOf(Objects.requireNonNullElse(drop, List.of()));
        if (drop.size() > FIELDS.size() || new LinkedHashSet<>(drop).size() != drop.size()) {
            throw new IllegalArgumentException("JSON dropped fields must be unique");
        }
        for (String field : drop) {
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("unknown dropped JSON field: " + field);
            }
        }
        if (drop.contains("timestamp")) {
            throw new IllegalArgumentException("JSON profiles may not drop timestamp");
        }
        if (drop.contains("body") && drop.contains("event_name")) {
            throw new IllegalArgumentException("JSON profiles may not drop both body and event_name");
        }
        attributes = Objects.requireNonNullElse(attributes, JsonAttributeTransformConfig.nested());
    }

    private static String outputName(String value) {
        String normalized = Objects.requireNonNull(value, "JSON field transform target").trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("JSON field transform target must be 1 to 128 characters");
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (Character.isISOControl(normalized.charAt(index))) {
                throw new IllegalArgumentException("JSON field transform target contains a control character");
            }
        }
        return normalized;
    }
}
