package com.logyard4j.logyard.config.loading.compiler;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.config.ConfigurationException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Destructive, path-aware reader for one strict configuration table. */
final class ConfigReader {
    private final Map<String, Object> remainingValues;
    private final ConfigSource context;
    private final String path;
    private final ConfigValueDecoder values;

    ConfigReader(Map<String, Object> values, ConfigSource context, String path) {
        remainingValues = new LinkedHashMap<>(values);
        this.context = context;
        this.path = path;
        this.values = new ConfigValueDecoder(this);
    }

    static ConfigReader fromValue(Object value, ConfigSource context, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw context.failure(path, "expected a table");
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw context.failure(path, "table key is not a string");
            }
            converted.put(key, entry.getValue());
        }
        return new ConfigReader(converted, context, path);
    }

    ConfigSource context() {
        return context;
    }

    String source() {
        return context.name();
    }

    Map<String, String> environment() {
        return context.environment();
    }

    boolean has(String key) {
        return remainingValues.containsKey(key);
    }

    boolean empty() {
        return remainingValues.isEmpty();
    }

    ConfigReader object(String key) {
        Object value = remainingValues.remove(key);
        return value == null
                ? new ConfigReader(Map.of(), context, childPath(key))
                : fromValue(value, context, childPath(key));
    }

    Map<String, Object> dynamicObject(String key) {
        Object value = remainingValues.remove(key);
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(fromValue(value, context, childPath(key)).remainingValues);
    }

    String requiredString(String key) {
        return values.requiredString(key);
    }

    String string(String key, String fallback) {
        return values.string(key, fallback);
    }

    String nullableString(String key) {
        return values.nullableString(key);
    }

    boolean bool(String key, boolean fallback) {
        return values.bool(key, fallback);
    }

    Boolean nullableBoolean(String key) {
        return values.nullableBoolean(key);
    }

    int integer(String key, int fallback) {
        return values.integer(key, fallback);
    }

    Integer nullableInteger(String key) {
        return values.nullableInteger(key);
    }

    long longInteger(String key, long fallback) {
        return values.longInteger(key, fallback);
    }

    double number(String key, double fallback) {
        return values.number(key, fallback);
    }

    Level level(String key, Level fallback) {
        return values.level(key, fallback);
    }

    Level nullableLevel(String key) {
        return values.nullableLevel(key);
    }

    Duration duration(String key, Duration fallback) {
        return values.duration(key, fallback);
    }

    long size(String key, long fallback) {
        return values.size(key, fallback);
    }

    int sizeAsInt(String key, int fallback) {
        return values.sizeAsInt(key, fallback);
    }

    List<String> stringList(String key, List<String> fallback) {
        return values.stringList(key, fallback);
    }

    List<String> nullableStringList(String key) {
        return values.nullableStringList(key);
    }

    void finish() {
        if (remainingValues.isEmpty()) {
            return;
        }
        String unknown = remainingValues.keySet().iterator().next();
        String suggestion = ConfigKeySuggestions.nearest(unknown, path);
        String message = "unknown key '" + unknown + "'";
        if (suggestion != null) {
            message += "; did you mean '" + suggestion + "'?";
        }
        throw failure(unknown, message);
    }

    ConfigurationException failure(String key, String message) {
        return context.failure(childPath(key), message);
    }

    ConfigurationException failureFrom(String key, IllegalArgumentException failure) {
        return context.failureFrom(childPath(key), failure);
    }

    /** Locates a failure at this reader's own table rather than under one of its keys. */
    ConfigurationException sectionFailureFrom(IllegalArgumentException failure) {
        return context.failureFrom(path, failure);
    }

    String childPath(String child) {
        return path.isEmpty() ? child : path + "." + child;
    }

    Object take(String key) {
        return remainingValues.remove(key);
    }

}
