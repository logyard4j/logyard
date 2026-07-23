package com.zsumz.logyard.config.toml;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record TomlDocument(Map<String, Object> root) {
    public TomlDocument {
        root = Collections.unmodifiableMap(new LinkedHashMap<>(root));
    }
}
