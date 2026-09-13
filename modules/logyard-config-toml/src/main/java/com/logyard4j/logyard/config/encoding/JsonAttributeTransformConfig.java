package com.logyard4j.logyard.config.encoding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded attribute filtering, renaming, and placement for a JSON profile. */
public record JsonAttributeTransformConfig(
        String mode,
        String prefix,
        List<String> include,
        List<String> exclude,
        Map<String, String> rename) {
    public JsonAttributeTransformConfig {
        mode = Objects.requireNonNullElse(mode, "nested").trim().toLowerCase(Locale.ROOT);
        if (!Set.of("nested", "flatten", "drop").contains(mode)) {
            throw new IllegalArgumentException("JSON attribute mode must be nested, flatten, or drop");
        }
        prefix = Objects.requireNonNullElse(prefix, "attributes.");
        if (prefix.length() > 128 || containsControl(prefix)) {
            throw new IllegalArgumentException("JSON attribute prefix exceeds its bound or contains controls");
        }
        if ("flatten".equals(mode) && prefix.isEmpty()) {
            throw new IllegalArgumentException("flattened JSON attributes require a non-empty prefix");
        }
        include = boundedNames(include, "JSON attribute include");
        exclude = boundedNames(exclude, "JSON attribute exclude");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        Objects.requireNonNullElse(rename, Map.<String, String>of()).forEach((key, value) -> {
            String source = attributeName(key, "JSON attribute rename source");
            String target = attributeName(value, "JSON attribute rename target");
            if (copy.put(source, target) != null) {
                throw new IllegalArgumentException("duplicate JSON attribute rename source: " + source);
            }
        });
        if (copy.size() > 128 || new LinkedHashSet<>(copy.values()).size() != copy.size()) {
            throw new IllegalArgumentException("JSON attribute renames must be unique and at most 128 entries");
        }
        rename = Collections.unmodifiableMap(copy);
    }

    public static JsonAttributeTransformConfig nested() {
        return new JsonAttributeTransformConfig("nested", "attributes.", List.of(), List.of(), Map.of());
    }

    private static List<String> boundedNames(List<String> values, String label) {
        List<String> copy = List.copyOf(Objects.requireNonNullElse(values, List.of()));
        if (copy.size() > 128 || new LinkedHashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(label + " must be unique and contain at most 128 entries");
        }
        for (String value : copy) {
            attributeName(value, label + " entry");
        }
        return copy;
    }

    private static String attributeName(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty() || normalized.length() > 256 || containsControl(normalized)) {
            throw new IllegalArgumentException(label + " must be 1 to 256 safe characters");
        }
        return normalized;
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
