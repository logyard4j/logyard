package com.zsumz.logyard.output.console;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Small, deterministic terminal policy used by the console output and CLI. */
public final class TerminalSupport {
    private TerminalSupport() {
    }

    public static boolean colorsEnabled(String mode) {
        return colorsEnabled(mode, System.console() != null, System.getenv());
    }

    public static ColorCapability colorCapability(String configured) {
        return colorCapability(configured, System.getenv());
    }

    static ColorCapability colorCapability(String configured, Map<String, String> environment) {
        Objects.requireNonNull(configured, "configured");
        Objects.requireNonNull(environment, "environment");
        return switch (configured.toLowerCase(Locale.ROOT)) {
            case "ansi16" -> ColorCapability.ANSI16;
            case "ansi256" -> ColorCapability.ANSI256;
            case "truecolor" -> ColorCapability.TRUECOLOR;
            case "auto" -> {
                String colorTerm = environment.getOrDefault("COLORTERM", "").toLowerCase(Locale.ROOT);
                String term = environment.getOrDefault("TERM", "").toLowerCase(Locale.ROOT);
                if (colorTerm.contains("truecolor") || colorTerm.contains("24bit")) {
                    yield ColorCapability.TRUECOLOR;
                }
                yield term.contains("256color") ? ColorCapability.ANSI256 : ColorCapability.ANSI16;
            }
            default -> throw new IllegalArgumentException(
                    "color capability must be auto, ansi16, ansi256, or truecolor");
        };
    }

    static boolean colorsEnabled(String mode, boolean terminal, Map<String, String> environment) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(environment, "environment");
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "always" -> true;
            case "never" -> false;
            case "auto" -> terminal
                    && !environment.containsKey("NO_COLOR")
                    && !"dumb".equalsIgnoreCase(environment.get("TERM"));
            default -> throw new IllegalArgumentException("color mode must be auto, always, or never");
        };
    }
}
