package com.logyard4j.config.toml;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable parsed TOML root table with the line positions of its keys. */
public record TomlDocument(Map<String, Object> root, TomlPositions positions) {
    public TomlDocument {
        root = Collections.unmodifiableMap(new LinkedHashMap<>(root));
        Objects.requireNonNull(positions, "positions");
    }

    /** Creates a document without recorded line positions. */
    public TomlDocument(Map<String, Object> root) {
        this(root, TomlPositions.empty());
    }
}
