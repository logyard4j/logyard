package com.logyard4j.config.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The declared key vocabulary of Logyard's TOML configuration schema.
 *
 * <p>Each table pattern maps to the keys that table accepts. A {@code *} segment stands
 * for one configured name, which may itself contain dots. An empty key set marks a
 * free-form table whose keys are configured names or provider-defined options. The
 * vocabulary powers scoped typo suggestions and generated configuration references;
 * decoders remain the enforcement authority.</p>
 */
public final class ConfigSchema {
    private static final Set<String> LEVEL_KEYS = Set.of("trace", "debug", "info", "warn", "error");
    private static final Set<String> STYLE_KEYS = Set.of("fg", "bg", "bold", "dim", "italic", "underline");
    private static final Set<String> PROVIDER_KEYS = Set.of("provider", "implementation", "config");
    private static final Map<String, Set<String>> TABLES = tables();

    private ConfigSchema() {
    }

    /** Returns the ordered table patterns and their accepted keys. */
    public static Map<String, Set<String>> describe() {
        return TABLES;
    }

    /**
     * Returns the accepted keys for one concrete table path, or an empty set when the
     * table is free-form or not part of the declared schema.
     */
    public static Set<String> keysFor(String tablePath) {
        if (tablePath.isEmpty()) {
            return TABLES.get("");
        }
        List<String> path = List.of(tablePath.split("\\.", -1));
        for (Map.Entry<String, Set<String>> entry : TABLES.entrySet()) {
            if (entry.getKey().isEmpty()) {
                continue;
            }
            if (matches(List.of(entry.getKey().split("\\.", -1)), 0, path, 0)) {
                return entry.getValue();
            }
        }
        return Set.of();
    }

    /** Returns the union of every declared key, for unscoped suggestions. */
    public static Set<String> allKeys() {
        Set<String> union = new LinkedHashSet<>();
        TABLES.values().forEach(union::addAll);
        return Collections.unmodifiableSet(union);
    }

    private static boolean matches(List<String> pattern, int p, List<String> path, int c) {
        if (p == pattern.size()) {
            return c == path.size();
        }
        if (c == path.size()) {
            return false;
        }
        if (pattern.get(p).equals("*")) {
            for (int consumed = c + 1; consumed <= path.size(); consumed++) {
                if (matches(pattern, p + 1, path, consumed)) {
                    return true;
                }
            }
            return false;
        }
        return pattern.get(p).equals(path.get(c)) && matches(pattern, p + 1, path, c + 1);
    }

    private static Map<String, Set<String>> tables() {
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        tables.put("", Set.of(
                "schema", "service", "resource", "runtime", "context", "delivery", "loggers",
                "outputs", "themes", "formatters", "encoders", "json_profiles", "enrichers", "filters",
                "profiles"));
        tables.put("service", Set.of("name", "namespace", "version", "environment", "instance_id"));
        tables.put("resource", Set.of("attributes", "include", "exclude"));
        tables.put("resource.attributes", Set.of());
        tables.put("runtime", Set.of("shutdown_timeout", "internal_status", "watch", "reload_debounce"));
        tables.put("context", Set.of("trace", "mdc", "baggage", "redact"));
        tables.put("delivery.overflow.*", Set.of("action", "timeout"));
        tables.put("delivery.overflow", LEVEL_KEYS);
        tables.put("delivery", Set.of("mode", "capacity", "overflow"));
        tables.put("loggers.*", Set.of("level", "outputs", "enrich", "filters"));
        tables.put("loggers", Set.of());
        tables.put("outputs.*.color", Set.of("mode", "capability", "theme"));
        tables.put("outputs.*.exception", Set.of("style", "common_frames"));
        tables.put("outputs.*.delivery", Set.of("mode", "capacity"));
        tables.put("outputs.*.rotate", Set.of("size", "keep", "compression", "interval"));
        tables.put("outputs.*.config", Set.of());
        tables.put("outputs.*", union(PROVIDER_KEYS, Set.of(
                "type", "min_level", "stream", "formatter", "encoder", "color", "exception",
                "delivery", "path", "buffer", "flush", "append", "fsync", "rotate")));
        tables.put("outputs", Set.of());
        tables.put("formatters.*.config", Set.of());
        tables.put("formatters.*", union(PROVIDER_KEYS, Set.of("type", "template")));
        tables.put("formatters", Set.of());
        tables.put("encoders.*.config", Set.of());
        tables.put("encoders.*", union(PROVIDER_KEYS, Set.of("type", "profile")));
        tables.put("encoders", Set.of());
        tables.put("json_profiles.*.attributes.rename", Set.of());
        tables.put("json_profiles.*.attributes", Set.of("mode", "prefix", "include", "exclude", "rename"));
        tables.put("json_profiles.*.rename", Set.of());
        tables.put("json_profiles.*", Set.of("preset", "rename", "drop", "attributes"));
        tables.put("json_profiles", Set.of());
        tables.put("enrichers.*.config", Set.of());
        tables.put("enrichers.*", PROVIDER_KEYS);
        tables.put("enrichers", Set.of());
        tables.put("filters.*.config", Set.of());
        tables.put("filters.*", union(PROVIDER_KEYS, Set.of(
                "type", "probability", "key", "seed", "permits_per_second", "burst", "max_keys")));
        tables.put("filters", Set.of());
        tables.put("profiles.*", Set.of());
        tables.put("profiles", Set.of());
        tables.put("themes.*.level.*", STYLE_KEYS);
        tables.put("themes.*.level", LEVEL_KEYS);
        tables.put("themes.*.*", STYLE_KEYS);
        tables.put("themes.*", Set.of(
                "timestamp", "logger", "thread", "event", "message", "field_key",
                "field_value", "punctuation", "exception", "stack_frame", "level"));
        tables.put("themes", Set.of());
        return Collections.unmodifiableMap(tables);
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return Collections.unmodifiableSet(union);
    }
}
