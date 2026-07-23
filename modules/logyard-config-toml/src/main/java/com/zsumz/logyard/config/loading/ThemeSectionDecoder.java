package com.zsumz.logyard.config.loading;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.config.theme.TextStyleConfig;
import com.zsumz.logyard.config.theme.ThemeConfig;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Decodes named semantic console themes and their level-specific styles. */
final class ThemeSectionDecoder {
    private static final Set<String> THEME_ROLES = Set.of(
            "timestamp", "logger", "thread", "event", "message", "field_key",
            "field_value", "punctuation", "exception", "stack_frame");

    private ThemeSectionDecoder() {
    }

    static Map<String, ThemeConfig> themes(Map<String, Object> raw, String source, Map<String, String> environment) {
        Map<String, ThemeConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String name = entry.getKey();
            ConfigReader theme = ConfigReader.fromValue(entry.getValue(), source, "themes." + name, environment);
            Map<String, TextStyleConfig> roles = roles(theme);
            EnumMap<Level, TextStyleConfig> levels = levels(theme, name, source, environment);
            theme.finish();
            result.put(name, new ThemeConfig(name, roles, levels));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, TextStyleConfig> roles(ConfigReader theme) {
        Map<String, TextStyleConfig> roles = new LinkedHashMap<>();
        for (String role : THEME_ROLES) {
            if (theme.has(role)) {
                roles.put(role, textStyle(theme.object(role)));
            }
        }
        return roles;
    }

    private static EnumMap<Level, TextStyleConfig> levels(
            ConfigReader theme,
            String themeName,
            String source,
            Map<String, String> environment) {
        EnumMap<Level, TextStyleConfig> levels = new EnumMap<>(Level.class);
        for (Map.Entry<String, Object> entry : theme.dynamicObject("level").entrySet()) {
            String path = "themes." + themeName + ".level." + entry.getKey();
            Level level = LogyardConfigLoader.parseLevel(entry.getKey(), source, path);
            levels.put(level, textStyle(ConfigReader.fromValue(entry.getValue(), source, path, environment)));
        }
        return levels;
    }

    private static TextStyleConfig textStyle(ConfigReader reader) {
        String foreground = reader.nullableString("fg");
        String background = reader.nullableString("bg");
        Boolean bold = reader.nullableBoolean("bold");
        Boolean dim = reader.nullableBoolean("dim");
        Boolean italic = reader.nullableBoolean("italic");
        Boolean underline = reader.nullableBoolean("underline");
        reader.finish();
        return new TextStyleConfig(foreground, background, bold, dim, italic, underline);
    }
}
