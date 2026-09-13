package com.logyard4j.logyard.runtime.tools;

import com.logyard4j.logyard.config.schema.ConfigSchema;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Prints the configuration key vocabulary as deterministic machine-readable JSON. */
final class ConfigSchemaCommand {
    private ConfigSchemaCommand() {
    }

    static int run(ToolArguments arguments, PrintStream out) {
        arguments.requireNoValues();
        StringBuilder json = new StringBuilder(4_096);
        json.append("{\n");
        json.append("  \"title\": \"Logyard configuration vocabulary\",\n");
        json.append("  \"schemaVersion\": 1,\n");
        json.append("  \"description\": \"Table patterns and the keys each table accepts."
                + " A * segment stands for one configured name. An empty key array marks"
                + " a free-form table.\",\n");
        json.append("  \"tables\": {\n");
        List<Map.Entry<String, Set<String>>> tables = new ArrayList<>(ConfigSchema.describe().entrySet());
        for (int index = 0; index < tables.size(); index++) {
            Map.Entry<String, Set<String>> table = tables.get(index);
            List<String> keys = new ArrayList<>(table.getValue());
            keys.sort(String::compareTo);
            json.append("    \"").append(table.getKey()).append("\": [");
            for (int keyIndex = 0; keyIndex < keys.size(); keyIndex++) {
                if (keyIndex > 0) {
                    json.append(", ");
                }
                json.append('"').append(keys.get(keyIndex)).append('"');
            }
            json.append(']').append(index + 1 < tables.size() ? "," : "").append('\n');
        }
        json.append("  }\n}");
        out.println(json);
        return 0;
    }
}
