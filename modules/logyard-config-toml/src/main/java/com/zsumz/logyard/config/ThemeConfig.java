package com.zsumz.logyard.config;

import com.zsumz.logyard.api.Level;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

public record ThemeConfig(
        String name,
        Map<String, TextStyleConfig> roles,
        Map<Level, TextStyleConfig> levels) {
    public ThemeConfig {
        roles = Collections.unmodifiableMap(new LinkedHashMap<>(roles));
        levels = Collections.unmodifiableMap(new EnumMap<>(levels));
    }
}
