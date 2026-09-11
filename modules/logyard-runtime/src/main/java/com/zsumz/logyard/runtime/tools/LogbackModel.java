package com.zsumz.logyard.runtime.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable model of one Logback configuration being converted to Logyard TOML. */
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

    void note(String note) {
        if (!notes.contains(note)) {
            notes.add(note);
        }
    }

    /** Substitutes locally declared Logback properties; environment references remain. */
    String substitute(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        String result = value;
        for (Map.Entry<String, String> property : properties.entrySet()) {
            result = result.replace("${" + property.getKey() + "}", property.getValue());
            int reference;
            while ((reference = result.indexOf("${" + property.getKey() + ":-")) >= 0) {
                int closing = result.indexOf('}', reference);
                if (closing < 0) {
                    break;
                }
                result = result.substring(0, reference) + property.getValue() + result.substring(closing + 1);
            }
        }
        return result;
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
