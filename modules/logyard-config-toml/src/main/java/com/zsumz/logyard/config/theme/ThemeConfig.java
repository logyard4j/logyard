package com.zsumz.logyard.config.theme;

import com.zsumz.logyard.api.Level;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ThemeConfig(
        String name,
        Map<String, TextStyleConfig> roles,
        Map<Level, TextStyleConfig> levels) {
    public ThemeConfig {
        roles = Collections.unmodifiableMap(new LinkedHashMap<>(roles));
        EnumMap<Level, TextStyleConfig> copiedLevels = new EnumMap<>(Level.class);
        copiedLevels.putAll(Objects.requireNonNull(levels, "levels"));
        levels = Collections.unmodifiableMap(copiedLevels);
    }
}
