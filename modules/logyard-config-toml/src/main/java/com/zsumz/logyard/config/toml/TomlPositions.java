package com.zsumz.logyard.config.toml;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable line positions for the keys and tables of one parsed TOML document.
 *
 * <p>Paths are dotted joins of raw key segments, matching the paths configuration
 * diagnostics report. Lookup is best-effort: a path without a recorded position falls
 * back to its nearest recorded ancestor so a diagnostic can point at the enclosing
 * assignment or table when the exact key has no line of its own.</p>
 */
public final class TomlPositions {
    private static final TomlPositions EMPTY = new TomlPositions(Map.of());

    private final Map<String, Integer> lines;

    private TomlPositions(Map<String, Integer> lines) {
        this.lines = lines;
    }

    static TomlPositions of(Map<String, Integer> lines) {
        return new TomlPositions(Collections.unmodifiableMap(new LinkedHashMap<>(lines)));
    }

    /** Returns positions with no recorded lines. */
    public static TomlPositions empty() {
        return EMPTY;
    }

    /** Returns the recorded one-based line for the exact path, or {@code -1}. */
    public int line(String path) {
        Integer line = lines.get(path);
        return line == null ? -1 : line;
    }

    /**
     * Returns the one-based line for the path or its nearest recorded ancestor.
     *
     * <p>A trailing {@code [index]} suffix on any segment is ignored so array-element
     * diagnostics resolve to the array assignment. Returns {@code -1} when neither the
     * path nor any ancestor has a recorded line.</p>
     */
    public int lineOrAncestor(String path) {
        String candidate = path;
        while (true) {
            int exact = line(stripIndex(candidate));
            if (exact >= 0) {
                return exact;
            }
            int dot = candidate.lastIndexOf('.');
            if (dot < 0) {
                return -1;
            }
            candidate = candidate.substring(0, dot);
        }
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
