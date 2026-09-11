package com.zsumz.logyard.runtime.tools;

import com.zsumz.logyard.core.diagnostics.EmergencyText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    /** Original async appender names mapped to original target names. */
    final Map<String, String> appenderAliases = new LinkedHashMap<>();
    private final Map<String, String> outputNames = new LinkedHashMap<>();
    private final Set<String> allocatedNames = new HashSet<>();
    final List<String> notes = new ArrayList<>();
    private MigrationOutcome outcome = MigrationOutcome.EXACT;
    private int remainingExpansion = MigrationProperties.MAX_CHARACTERS;

    LogbackModel() {
        delivery.put("mode", "sync");
    }

    void note(String note) {
        if (outcome == MigrationOutcome.EXACT) outcome = MigrationOutcome.LOSSY;
        String bounded = EmergencyText.sanitize(note, 1_024);
        if (notes.size() < 128 && !notes.contains(bounded)) {
            notes.add(bounded);
        } else if (notes.size() == 128) {
            notes.add("additional migration notes omitted; review the original configuration");
        }
    }

    void unsupported(String note) {
        outcome = MigrationOutcome.UNSUPPORTED;
        note(note);
    }

    MigrationOutcome outcome() {
        return outcome;
    }

    /** Substitutes bounded local properties while retaining environment expressions. */
    String substitute(String value) {
        String result = MigrationProperties.expand(value, properties, remainingExpansion);
        if (result != null) remainingExpansion -= result.length();
        return result;
    }

    /** Allocates a stable, collision-free output identity without changing reference case. */
    String outputName(String original) {
        return outputNames.computeIfAbsent(original, name -> {
            String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
            if (base.isEmpty() || base.charAt(0) < 'a' || base.charAt(0) > 'z') base = "output-" + base;
            base = base.substring(0, Math.min(base.length(), 48));
            String candidate = base;
            for (int suffix = 2; !allocatedNames.add(candidate); suffix++) candidate = base + "-" + suffix;
            return candidate;
        });
    }

    /** Resolves original names only; generated names never participate in alias lookup. */
    String resolveOutput(String name) {
        Set<String> visited = new HashSet<>();
        String current = name;
        while (current != null && visited.add(current)) {
            String output = outputNames.get(current);
            if (output != null && outputs.containsKey(output)) return output;
            current = appenderAliases.get(current);
        }
        return null;
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
