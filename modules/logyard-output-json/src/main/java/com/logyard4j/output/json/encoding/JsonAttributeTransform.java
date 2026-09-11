package com.logyard4j.output.json.encoding;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable attribute filtering, renaming, and placement used by a JSON profile. */
public final class JsonAttributeTransform {
    public enum Mode {
        NESTED,
        FLATTEN,
        DROP;

        public static Mode parse(String value) {
            try {
                return valueOf(Objects.requireNonNull(value, "attribute mode")
                        .trim()
                        .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "JSON attribute mode must be nested, flatten, or drop", exception);
            }
        }
    }

    private final Mode mode;
    private final String prefix;
    private final Set<String> include;
    private final Set<String> exclude;
    private final Map<String, String> rename;

    public JsonAttributeTransform(
            Mode mode,
            String prefix,
            List<String> include,
            List<String> exclude,
            Map<String, String> rename) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.prefix = safe(Objects.requireNonNullElse(prefix, "attributes."), "attribute prefix", 128, true);
        if (mode == Mode.FLATTEN && this.prefix.isEmpty()) {
            throw new IllegalArgumentException("flattened JSON attributes require a non-empty prefix");
        }
        this.include = names(include, "attribute include");
        this.exclude = names(exclude, "attribute exclude");
        LinkedHashMap<String, String> renamed = new LinkedHashMap<>();
        Objects.requireNonNullElse(rename, Map.<String, String>of()).forEach((source, target) -> {
            String normalizedSource = safe(source, "attribute rename source", 256, false);
            String normalizedTarget = safe(target, "attribute rename target", 256, false);
            if (renamed.putIfAbsent(normalizedSource, normalizedTarget) != null) {
                throw new IllegalArgumentException(
                        "duplicate JSON attribute rename source: " + normalizedSource);
            }
        });
        if (renamed.size() > 128 || new LinkedHashSet<>(renamed.values()).size() != renamed.size()) {
            throw new IllegalArgumentException(
                    "JSON attribute renames must be unique and contain at most 128 entries");
        }
        this.rename = Map.copyOf(renamed);
    }

    public static JsonAttributeTransform nested() {
        return new JsonAttributeTransform(Mode.NESTED, "attributes.", List.of(), List.of(), Map.of());
    }

    public Mode mode() {
        return mode;
    }

    public String prefix() {
        return prefix;
    }

    public boolean includes(String name) {
        return (include.isEmpty() || include.contains(name)) && !exclude.contains(name);
    }

    public String outputName(String name) {
        return rename.getOrDefault(name, name);
    }

    boolean requiresCollisionCheck() {
        return !rename.isEmpty();
    }

    private static Set<String> names(List<String> values, String label) {
        List<String> supplied = Objects.requireNonNullElse(values, List.of());
        if (supplied.size() > 128) {
            throw new IllegalArgumentException(label + " contains more than 128 entries");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : supplied) {
            String normalized = safe(value, label + " entry", 256, false);
            if (!result.add(normalized)) {
                throw new IllegalArgumentException(label + " contains duplicate '" + normalized + "'");
            }
        }
        return Set.copyOf(result);
    }

    private static String safe(
            String value,
            String label,
            int maximum,
            boolean allowEmpty) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if ((!allowEmpty && normalized.isEmpty()) || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    label + " must be " + (allowEmpty ? "0" : "1") + " to " + maximum + " characters");
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (Character.isISOControl(normalized.charAt(index))) {
                throw new IllegalArgumentException(label + " contains a control character");
            }
        }
        return normalized;
    }
}
