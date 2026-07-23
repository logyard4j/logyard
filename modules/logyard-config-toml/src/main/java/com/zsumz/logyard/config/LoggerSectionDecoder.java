package com.zsumz.logyard.config;

import com.zsumz.logyard.api.Level;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Decodes the root logger and hierarchically inheritable child logger rules. */
final class LoggerSectionDecoder {
    private LoggerSectionDecoder() {
    }

    static Bundle decode(
            Map<String, Object> raw,
            Map<String, OutputConfig> outputs,
            String source,
            Map<String, String> environment) {
        Object rootValue = raw.remove("root");
        LoggerRuleConfig root = rootValue == null
                ? new LoggerRuleConfig(Level.INFO, List.copyOf(outputs.keySet()), List.of(), List.of())
                : rule(rootValue, true, outputs, source, "loggers.root", environment);
        if (root.level() == null) {
            root = new LoggerRuleConfig(Level.INFO, root.outputs(), root.enrich(), root.filters());
        }
        if (root.outputs() == null || root.outputs().isEmpty()) {
            root = new LoggerRuleConfig(root.level(), List.copyOf(outputs.keySet()), root.enrich(), root.filters());
        }
        if (root.enrich() == null) {
            root = new LoggerRuleConfig(root.level(), root.outputs(), List.of(), root.filters());
        }
        if (root.filters() == null) {
            root = new LoggerRuleConfig(root.level(), root.outputs(), root.enrich(), List.of());
        }

        Map<String, LoggerRuleConfig> children = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getKey().isBlank()) {
                throw new ConfigurationException(source + ": loggers: logger name must not be blank");
            }
            children.put(entry.getKey(), rule(entry.getValue(), false, outputs, source, "loggers." + entry.getKey(), environment));
        }
        return new Bundle(root, Collections.unmodifiableMap(children));
    }

    private static LoggerRuleConfig rule(
            Object value,
            boolean root,
            Map<String, OutputConfig> outputs,
            String source,
            String path,
            Map<String, String> environment) {
        if (value instanceof String text) {
            return new LoggerRuleConfig(
                    LogyardConfigLoader.parseLevel(text, source, path),
                    root ? List.copyOf(outputs.keySet()) : null,
                    root ? List.of() : null,
                    root ? List.of() : null);
        }

        ConfigReader reader = ConfigReader.fromValue(value, source, path, environment);
        Level level = root ? reader.level("level", Level.INFO) : reader.nullableLevel("level");
        List<String> selectedOutputs =
                root ? reader.stringList("outputs", List.copyOf(outputs.keySet())) : reader.nullableStringList("outputs");
        if (selectedOutputs != null) {
            for (String output : selectedOutputs) {
                if (!outputs.containsKey(output)) {
                    throw reader.failure("outputs", "unknown output '" + output + "'");
                }
            }
        }
        List<String> enrich = root ? reader.stringList("enrich", List.of()) : reader.nullableStringList("enrich");
        List<String> filters = root ? reader.stringList("filters", List.of()) : reader.nullableStringList("filters");
        reader.finish();
        return new LoggerRuleConfig(level, selectedOutputs, enrich, filters);
    }

    record Bundle(LoggerRuleConfig root, Map<String, LoggerRuleConfig> children) {
    }
}
