package com.logyard4j.logyard.config.loading.overlay;

import com.logyard4j.logyard.config.ConfigurationException;
import com.logyard4j.logyard.config.toml.TomlFragment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Copying merge and override application over parsed TOML tables with origin recording. */
final class OverlayMerge {
    private OverlayMerge() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepCopy(Map<String, Object> table) {
        Map<String, Object> copy = new LinkedHashMap<>(table.size());
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            copy.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return deepCopy((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(copyValue(element));
            }
            return copy;
        }
        return value;
    }

    /** Merges the overlay into the target: tables merge recursively, other values replace. */
    @SuppressWarnings("unchecked")
    static void merge(
            Map<String, Object> target,
            Map<String, Object> overlay,
            String path,
            Function<String, String> originFor,
            Map<String, String> origins) {
        for (Map.Entry<String, Object> entry : overlay.entrySet()) {
            String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            Object existing = target.get(entry.getKey());
            if (existing instanceof Map<?, ?> existingTable && entry.getValue() instanceof Map<?, ?> overlayTable) {
                merge(
                        (Map<String, Object>) existingTable,
                        (Map<String, Object>) overlayTable,
                        childPath,
                        originFor,
                        origins);
                continue;
            }
            target.put(entry.getKey(), copyValue(entry.getValue()));
            recordTree(origins, childPath, entry.getValue(), originFor.apply(childPath));
        }
    }

    /** Replaces the value at the fragment's key path, creating intermediate tables. */
    @SuppressWarnings("unchecked")
    static void applyOverride(
            Map<String, Object> root,
            TomlFragment fragment,
            String origin,
            Map<String, String> origins) {
        List<String> path = fragment.path();
        Map<String, Object> target = root;
        for (int index = 0; index < path.size() - 1; index++) {
            String segment = path.get(index);
            Object existing = target.get(segment);
            if (existing == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                target.put(segment, created);
                target = created;
            } else if (existing instanceof Map<?, ?> table) {
                target = (Map<String, Object>) table;
            } else {
                throw new ConfigurationException(
                        origin + ": override path collides with an existing value at '" + segment + "'");
            }
        }
        target.put(path.getLast(), fragment.value());
        recordTree(origins, String.join(".", path), fragment.value(), origin);
    }

    private static void recordTree(Map<String, String> origins, String path, Object value, String origin) {
        origins.keySet().removeIf(existing -> existing.equals(path)
                || existing.startsWith(path + ".")
                || existing.startsWith(path + "["));
        record(origins, path, value, origin);
    }

    private static void record(Map<String, String> origins, String path, Object value, String origin) {
        origins.put(path, origin);
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                record(origins, path + "." + entry.getKey(), entry.getValue(), origin);
            }
        } else if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                Object element = list.get(index);
                if (element instanceof Map<?, ?> || element instanceof List<?>) {
                    record(origins, path + "[" + index + "]", element, origin);
                }
            }
        }
    }
}
