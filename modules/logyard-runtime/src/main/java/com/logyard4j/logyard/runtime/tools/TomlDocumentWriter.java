package com.logyard4j.logyard.runtime.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Writes a nested configuration model as readable TOML with stable section order. */
final class TomlDocumentWriter {
    private TomlDocumentWriter() {
    }

    static String write(Map<String, Object> document) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?>)) {
                text.append(TomlRender.key(entry.getKey())).append(" = ")
                        .append(TomlRender.value(entry.getValue())).append('\n');
            }
        }
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> table) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) table;
                writeTable(text, List.of(entry.getKey()), typed);
            }
        }
        return text.toString();
    }

    private static void writeTable(StringBuilder text, List<String> path, Map<String, Object> table) {
        if (table.isEmpty()) {
            return;
        }
        text.append('\n').append('[');
        for (int index = 0; index < path.size(); index++) {
            if (index > 0) {
                text.append('.');
            }
            text.append(TomlRender.key(path.get(index)));
        }
        text.append("]\n");
        List<Map.Entry<String, Object>> subTables = new ArrayList<>();
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> nested && !inline(path)) {
                subTables.add(Map.entry(entry.getKey(), (Object) nested));
                continue;
            }
            text.append(TomlRender.key(entry.getKey())).append(" = ")
                    .append(TomlRender.value(entry.getValue())).append('\n');
        }
        for (Map.Entry<String, Object> entry : subTables) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) entry.getValue();
            List<String> childPath = new ArrayList<>(path);
            childPath.add(entry.getKey());
            writeTable(text, childPath, typed);
        }
    }

    /** Tables whose map values render inline: logger rules and the options of one output. */
    private static boolean inline(List<String> path) {
        if (path.getLast().equals("loggers")) {
            return true;
        }
        return path.size() == 2 && path.getFirst().equals("outputs");
    }
}
