package com.zsumz.logyard.runtime.tools;

import java.util.Locale;

/** Maps Logback level names onto Logyard's five levels, reporting lossy mappings. */
final class LogbackLevels {
    private LogbackLevels() {
    }

    static String map(String level, LogbackModel model, String context) {
        String normalized = level.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "TRACE", "ALL" -> "trace";
            case "DEBUG" -> "debug";
            case "INFO" -> "info";
            case "WARN" -> "warn";
            case "ERROR" -> "error";
            case "FATAL" -> {
                model.note(context + ": level FATAL maps to error");
                yield "error";
            }
            case "OFF" -> {
                model.note(context + ": level OFF has no Logyard equivalent; 'error' was used —"
                        + " route the logger to no outputs to silence it completely");
                yield "error";
            }
            default -> {
                model.note(context + ": unknown level '" + level + "' maps to info");
                yield "info";
            }
        };
    }
}
