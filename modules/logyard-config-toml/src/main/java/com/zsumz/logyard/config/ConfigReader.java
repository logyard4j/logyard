package com.zsumz.logyard.config;

import com.zsumz.logyard.api.Level;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Destructive, path-aware reader for one strict configuration table. */
final class ConfigReader {
    private final Map<String, Object> values;
    private final String source;
    private final String path;
    private final Map<String, String> environment;

    ConfigReader(Map<String, Object> values, String source, String path, Map<String, String> environment) {
        this.values = new LinkedHashMap<>(values);
        this.source = source;
        this.path = path;
        this.environment = environment;
    }

    static ConfigReader fromValue(Object value, String source, String path, Map<String, String> environment) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new ConfigurationException(source + ": " + path + ": expected a table");
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new ConfigurationException(source + ": " + path + ": table key is not a string");
            }
            converted.put(key, entry.getValue());
        }
        return new ConfigReader(converted, source, path, environment);
    }

    String source() {
        return source;
    }

    Map<String, String> environment() {
        return environment;
    }

    boolean has(String key) {
        return values.containsKey(key);
    }

    boolean empty() {
        return values.isEmpty();
    }

    ConfigReader object(String key) {
        Object value = values.remove(key);
        return value == null
                ? new ConfigReader(Map.of(), source, childPath(key), environment)
                : fromValue(value, source, childPath(key), environment);
    }

    Map<String, Object> dynamicObject(String key) {
        Object value = values.remove(key);
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(fromValue(value, source, childPath(key), environment).values);
    }

    String requiredString(String key) {
        String value = nullableString(key);
        if (value == null) {
            throw failure(key, "is required");
        }
        return value;
    }

    String string(String key, String fallback) {
        String value = nullableString(key);
        return value == null ? fallback : value;
    }

    String nullableString(String key) {
        Object value = values.remove(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String string)) {
            throw failure(key, "expected a string");
        }
        return EnvironmentExpander.expand(string, environment, source, childPath(key));
    }

    boolean bool(String key, boolean fallback) {
        Object value = values.remove(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean flag)) {
            throw failure(key, "expected true or false");
        }
        return flag;
    }

    Boolean nullableBoolean(String key) {
        Object value = values.remove(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Boolean flag)) {
            throw failure(key, "expected true or false");
        }
        return flag;
    }

    int integer(String key, int fallback) {
        Integer value = nullableInteger(key);
        return value == null ? fallback : value;
    }

    Integer nullableInteger(String key) {
        Object value = values.remove(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Long number)) {
            throw failure(key, "expected an integer");
        }
        try {
            return Math.toIntExact(number);
        } catch (ArithmeticException exception) {
            throw failure(key, "integer is outside the supported range");
        }
    }

    long longInteger(String key, long fallback) {
        Object value = values.remove(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Long number)) {
            throw failure(key, "expected an integer");
        }
        return number;
    }

    double number(String key, double fallback) {
        Object value = values.remove(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Long integer) {
            return integer.doubleValue();
        }
        if (value instanceof Double decimal) {
            return decimal;
        }
        throw failure(key, "expected a number");
    }

    Level level(String key, Level fallback) {
        String value = nullableString(key);
        return value == null ? fallback : LogyardConfigLoader.parseLevel(value, source, childPath(key));
    }

    Level nullableLevel(String key) {
        String value = nullableString(key);
        return value == null ? null : LogyardConfigLoader.parseLevel(value, source, childPath(key));
    }

    Duration duration(String key, Duration fallback) {
        String value = nullableString(key);
        if (value == null) {
            return fallback;
        }
        try {
            return DurationParser.parse(value);
        } catch (RuntimeException exception) {
            throw failure(key, exception.getMessage());
        }
    }

    long size(String key, long fallback) {
        String value = nullableString(key);
        if (value == null) {
            return fallback;
        }
        try {
            return SizeParser.parse(value);
        } catch (RuntimeException exception) {
            throw failure(key, exception.getMessage());
        }
    }

    int sizeAsInt(String key, int fallback) {
        long value = size(key, fallback);
        if (value > Integer.MAX_VALUE) {
            throw failure(key, "must be at most " + Integer.MAX_VALUE + " bytes");
        }
        return (int) value;
    }

    List<String> stringList(String key, List<String> fallback) {
        List<String> value = nullableStringList(key);
        return value == null ? fallback : value;
    }

    List<String> nullableStringList(String key) {
        Object value = values.remove(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw failure(key, "expected an array of strings");
        }
        if (list.size() > ContextConfig.MAX_ALLOWLIST_ENTRIES) {
            throw failure(key, "contains too many values");
        }

        List<String> result = new ArrayList<>(list.size());
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < list.size(); index++) {
            Object item = list.get(index);
            if (!(item instanceof String text)) {
                throw failure(key + "[" + index + "]", "expected a string");
            }
            String expanded = EnvironmentExpander.expand(text, environment, source, childPath(key + "[" + index + "]"));
            if (!seen.add(expanded)) {
                throw failure(key, "contains duplicate value '" + expanded + "'");
            }
            result.add(expanded);
        }
        return List.copyOf(result);
    }

    void finish() {
        if (values.isEmpty()) {
            return;
        }
        String unknown = values.keySet().iterator().next();
        String suggestion = ConfigKeySuggestions.nearest(unknown);
        String message = "unknown key '" + unknown + "'";
        if (suggestion != null) {
            message += "; did you mean '" + suggestion + "'?";
        }
        throw failure(unknown, message);
    }

    ConfigurationException failure(String key, String message) {
        return new ConfigurationException(source + ": " + childPath(key) + ": " + message);
    }

    String childPath(String child) {
        return path.isEmpty() ? child : path + "." + child;
    }

}
