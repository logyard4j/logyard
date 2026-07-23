package com.zsumz.logyard.output.console;

import com.zsumz.logyard.api.Level;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Styles semantic console roles instead of exposing a pattern language. */
public final class ConsoleTheme {
    private final String name;
    private final Map<String, AnsiStyle> roles;
    private final Map<Level, AnsiStyle> levels;

    public ConsoleTheme(String name, Map<String, AnsiStyle> roles, Map<Level, AnsiStyle> levels) {
        this.name = requireName(name);
        this.roles = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(roles, "roles")));
        EnumMap<Level, AnsiStyle> copy = new EnumMap<>(Level.class);
        copy.putAll(Objects.requireNonNull(levels, "levels"));
        this.levels = Collections.unmodifiableMap(copy);
    }

    public String name() {
        return name;
    }

    public AnsiStyle role(String role) {
        return roles.getOrDefault(role, AnsiStyle.PLAIN);
    }

    public AnsiStyle level(Level level) {
        return levels.getOrDefault(level, AnsiStyle.PLAIN);
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        if (value.isBlank()) {
            throw new IllegalArgumentException("theme name must not be blank");
        }
        return value;
    }
}
