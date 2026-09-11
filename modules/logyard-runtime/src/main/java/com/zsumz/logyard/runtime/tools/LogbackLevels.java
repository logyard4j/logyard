package com.zsumz.logyard.runtime.tools;

import java.util.Locale;
import java.util.List;
import java.util.Map;

/** Maps Logback level names onto Logyard's five levels, reporting lossy mappings. */
final class LogbackLevels {
    private LogbackLevels() {
    }

    static void loggerRule(String level, Map<String, Object> rule, LogbackModel model, String context) {
        if (level == null) return;
        if ("OFF".equalsIgnoreCase(level.trim())) {
            rule.put("level", "error");
            rule.put("outputs", List.of());
            model.note(context + ": level OFF became an empty output route; descendants that explicitly"
                    + " enable logging need their own outputs");
        } else {
            rule.put("level", map(level, model, context));
        }
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
