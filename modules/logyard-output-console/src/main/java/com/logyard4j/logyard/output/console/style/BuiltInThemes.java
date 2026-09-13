package com.logyard4j.logyard.output.console.style;

import com.logyard4j.logyard.api.Level;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Built-in themes are intentionally small, legible, and semantic. */
public final class BuiltInThemes {
    private BuiltInThemes() {
    }

    public static ConsoleTheme named(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "ember" -> ember();
            case "nord" -> nord();
            case "mono" -> mono();
            default -> throw new IllegalArgumentException("unknown built-in Logyard theme: " + name);
        };
    }

    public static ConsoleTheme ember() {
        return theme(
                "ember",
                Map.of(
                        "timestamp", style("#808080", false, true),
                        "logger", style("#5f87af", false, false),
                        "thread", style("#808080", false, true),
                        "event", style("#d7875f", true, false),
                        "field_key", style("#808080", false, false),
                        "field_value", style("#d0d0d0", false, false),
                        "exception", style("#ff5f5f", true, false),
                        "stack_frame", style("#a8a8a8", false, true)),
                style("#808080", false, false),
                style("#5f87ff", false, false),
                style("#5fd75f", true, false),
                style("#ffaf00", true, false),
                style("#ff5f5f", true, false));
    }

    public static ConsoleTheme nord() {
        return theme(
                "nord",
                Map.of(
                        "timestamp", style("#616e88", false, true),
                        "logger", style("#81a1c1", false, false),
                        "thread", style("#616e88", false, true),
                        "event", style("#b48ead", true, false),
                        "field_key", style("#88c0d0", false, false),
                        "field_value", style("#d8dee9", false, false),
                        "exception", style("#bf616a", true, false),
                        "stack_frame", style("#7b88a1", false, true)),
                style("#616e88", false, false),
                style("#5e81ac", false, false),
                style("#a3be8c", true, false),
                style("#ebcb8b", true, false),
                style("#bf616a", true, false));
    }

    public static ConsoleTheme mono() {
        AnsiStyle dim = new AnsiStyle(null, null, false, true, false, false);
        EnumMap<Level, AnsiStyle> levels = new EnumMap<>(Level.class);
        levels.put(Level.TRACE, dim);
        levels.put(Level.DEBUG, dim);
        levels.put(Level.INFO, AnsiStyle.PLAIN);
        levels.put(Level.WARN, new AnsiStyle(null, null, true, false, false, false));
        levels.put(Level.ERROR, new AnsiStyle(null, null, true, false, false, true));
        return new ConsoleTheme("mono", Map.of("timestamp", dim, "thread", dim, "stack_frame", dim), levels);
    }

    private static ConsoleTheme theme(
            String name,
            Map<String, AnsiStyle> roles,
            AnsiStyle trace,
            AnsiStyle debug,
            AnsiStyle info,
            AnsiStyle warn,
            AnsiStyle error) {
        EnumMap<Level, AnsiStyle> levels = new EnumMap<>(Level.class);
        levels.put(Level.TRACE, trace);
        levels.put(Level.DEBUG, debug);
        levels.put(Level.INFO, info);
        levels.put(Level.WARN, warn);
        levels.put(Level.ERROR, error);
        return new ConsoleTheme(name, roles, levels);
    }

    private static AnsiStyle style(String foreground, boolean bold, boolean dim) {
        return new AnsiStyle(foreground, null, bold, dim, false, false);
    }
}
