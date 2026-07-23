package com.zsumz.logyard.config.toml;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds TOML tables while enforcing duplicate definitions and path-collision rules. */
final class TomlTableBuilder {
    private final TomlCursor cursor;
    private final Map<String, Object> root = new LinkedHashMap<>();
    private final Set<String> explicitTables = new HashSet<>();
    private Map<String, Object> current = root;

    TomlTableBuilder(TomlCursor cursor) {
        this.cursor = cursor;
    }

    TomlDocument document() {
        return new TomlDocument(root);
    }

    Map<String, Object> current() {
        return current;
    }

    void select(List<String> path, boolean array) {
        current = array ? createArrayTable(path) : createTable(path);
    }

    @SuppressWarnings("unchecked")
    void putPath(Map<String, Object> table, List<String> path, Object value) {
        Map<String, Object> target = table;
        for (int index = 0; index < path.size() - 1; index++) {
            String segment = path.get(index);
            Object existing = target.get(segment);
            if (existing == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                target.put(segment, created);
                target = created;
            } else if (existing instanceof Map<?, ?> map) {
                target = (Map<String, Object>) map;
            } else {
                cursor.fail("dotted key collides with existing value at '" + segment + "'");
            }
        }
        String leaf = path.getLast();
        if (target.putIfAbsent(leaf, value) != null) {
            cursor.fail("duplicate key '" + String.join(".", path) + "'");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createTable(List<String> path) {
        String tableName = String.join(".", path);
        if (!explicitTables.add(tableName)) {
            cursor.fail("table '[" + tableName + "]' is already defined");
        }
        Map<String, Object> target = root;
        for (String segment : path) {
            Object existing = target.get(segment);
            if (existing instanceof List<?> list) {
                if (list.isEmpty() || !(list.getLast() instanceof Map<?, ?>)) {
                    cursor.fail("table path '" + tableName + "' has no current array element");
                }
                target = (Map<String, Object>) list.getLast();
            } else if (existing == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                target.put(segment, created);
                target = created;
            } else if (existing instanceof Map<?, ?> map) {
                target = (Map<String, Object>) map;
            } else {
                cursor.fail("table path collides with an existing value at '" + segment + "'");
            }
        }
        return target;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createArrayTable(List<String> path) {
        if (path.isEmpty()) {
            cursor.fail("array table path must not be empty");
        }
        Map<String, Object> parent = root;
        for (int index = 0; index < path.size() - 1; index++) {
            String segment = path.get(index);
            Object existing = parent.get(segment);
            if (existing instanceof List<?> list) {
                if (list.isEmpty() || !(list.getLast() instanceof Map<?, ?>)) {
                    cursor.fail("array table parent has no current element");
                }
                parent = (Map<String, Object>) list.getLast();
            } else if (existing instanceof Map<?, ?> map) {
                parent = (Map<String, Object>) map;
            } else if (existing == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                parent.put(segment, created);
                parent = created;
            } else {
                cursor.fail("array table path collides with an existing value at '" + segment + "'");
            }
        }
        String leaf = path.getLast();
        Object existing = parent.get(leaf);
        List<Object> elements;
        if (existing == null) {
            elements = new ArrayList<>();
            parent.put(leaf, elements);
        } else if (existing instanceof List<?> list) {
            elements = (List<Object>) list;
        } else {
            cursor.fail("array table path collides with an existing value at '" + leaf + "'");
            throw new AssertionError("unreachable");
        }
        Map<String, Object> element = new LinkedHashMap<>();
        elements.add(element);
        return element;
    }
}
