package com.zsumz.logyard.config.loading.result;

import com.zsumz.logyard.config.toml.TomlPositions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves where each effective configuration value came from.
 *
 * <p>A location is a human-readable origin: the configuration file and line for values
 * written there, a profile origin for values a selected profile supplied, or the
 * override origin for values injected through system properties or the environment.
 * Lookup walks from the exact path to its nearest ancestor so nested values inherit
 * the origin of the assignment that produced them, and the most specific recorded
 * origin always wins.</p>
 */
public final class ConfigLocations {
    private final String sourceName;
    private final TomlPositions positions;
    private final Map<String, String> overlays;

    private ConfigLocations(String sourceName, TomlPositions positions, Map<String, String> overlays) {
        this.sourceName = sourceName;
        this.positions = positions;
        this.overlays = overlays;
    }

    /** Creates locations for a document without profile or override overlays. */
    public static ConfigLocations root(String sourceName, TomlPositions positions) {
        return of(sourceName, positions, Map.of());
    }

    /** Creates locations with overlay origins that shadow the document's own lines. */
    public static ConfigLocations of(String sourceName, TomlPositions positions, Map<String, String> overlays) {
        return new ConfigLocations(
                Objects.requireNonNull(sourceName, "sourceName"),
                Objects.requireNonNull(positions, "positions"),
                Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(overlays, "overlays"))));
    }

    /** Returns the source name these locations describe. */
    public String sourceName() {
        return sourceName;
    }

    /**
     * Returns the origin of the value at one configuration path, or {@code null} when
     * neither the path nor any ancestor has a recorded origin.
     */
    public String describe(String path) {
        String candidate = path;
        while (true) {
            String exact = exactOrigin(stripIndex(candidate));
            if (exact != null) {
                return exact;
            }
            int dot = candidate.lastIndexOf('.');
            if (dot < 0) {
                return null;
            }
            candidate = candidate.substring(0, dot);
        }
    }

    private String exactOrigin(String path) {
        String overlay = overlays.get(path);
        if (overlay != null) {
            return overlay;
        }
        int line = positions.line(path);
        return line >= 0 ? sourceName + ":" + line : null;
    }

    private static String stripIndex(String path) {
        if (path.endsWith("]")) {
            int bracket = path.lastIndexOf('[');
            if (bracket > 0) {
                return path.substring(0, bracket);
            }
        }
        return path;
    }
}
