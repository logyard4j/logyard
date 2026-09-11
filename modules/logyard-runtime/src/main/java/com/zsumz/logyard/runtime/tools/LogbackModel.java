package com.zsumz.logyard.runtime.tools;

import com.zsumz.logyard.core.diagnostics.EmergencyText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Mutable model of one XML logging configuration being converted to Logyard TOML. */
final class LogbackModel {
    final Map<String, Object> service = new LinkedHashMap<>();
    final Map<String, Object> runtime = new LinkedHashMap<>();
    final Map<String, Object> delivery = new LinkedHashMap<>();
    final Map<String, Object> loggers = new LinkedHashMap<>();
    final Map<String, Object> formatters = new LinkedHashMap<>();
    final Map<String, Object> outputs = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> profiles = new LinkedHashMap<>();
    final Map<String, String> properties = new LinkedHashMap<>();
    /** Appender name to Logyard output name, including async aliases to their targets. */
    final Map<String, String> appenderAliases = new LinkedHashMap<>();
    final List<String> notes = new ArrayList<>();
    private int remainingExpansion = MigrationProperties.MAX_CHARACTERS;

    void note(String note) {
        String bounded = EmergencyText.sanitize(note, 1_024);
        if (notes.size() < 128 && !notes.contains(bounded)) {
            notes.add(bounded);
        } else if (notes.size() == 128) {
            notes.add("additional migration notes omitted; review the original configuration");
        }
    }

    /** Substitutes bounded local properties while retaining environment expressions. */
    String substitute(String value) {
        String result = MigrationProperties.expand(value, properties, remainingExpansion);
        if (result != null) remainingExpansion -= result.length();
        return result;
    }

    /**
     * Resolves an appender reference through async aliases to a converted output name.
     *
     * @return the output name, or {@code null} when the reference converted to nothing
     */
    String resolveOutput(String name) {
        String current = name;
        for (int hops = 0; hops < 8 && current != null; hops++) {
            if (outputs.containsKey(current.toLowerCase(Locale.ROOT))
                    && !appenderAliases.containsKey(current)) {
                break;
            }
            String next = appenderAliases.get(current);
            if (next == null || next.equals(current)) {
                current = next;
                break;
            }
            current = next;
        }
        return current != null && outputs.containsKey(current.toLowerCase(Locale.ROOT))
                ? current.toLowerCase(Locale.ROOT)
                : null;
    }

    Map<String, Object> toDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schema", 1L);
        if (!service.isEmpty()) {
            document.put("service", service);
        }
        if (!runtime.isEmpty()) {
            document.put("runtime", runtime);
        }
        if (!delivery.isEmpty()) {
            document.put("delivery", delivery);
        }
        document.put("loggers", loggers);
        if (!formatters.isEmpty()) {
            document.put("formatters", formatters);
        }
        document.put("outputs", outputs);
        if (!profiles.isEmpty()) {
            document.put("profiles", profiles);
        }
        return document;
    }
}
