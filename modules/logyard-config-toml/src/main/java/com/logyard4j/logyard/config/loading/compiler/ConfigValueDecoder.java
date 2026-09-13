package com.logyard4j.logyard.config.loading.compiler;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.config.runtime.ContextConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Decodes and expands scalar values while the owning table tracks consumed keys. */
final class ConfigValueDecoder {
    private final ConfigReader table;

    ConfigValueDecoder(ConfigReader table) {
        this.table = table;
    }

    String requiredString(String key) {
        String value = nullableString(key);
        if (value == null) {
            throw table.failure(key, "is required");
        }
        return value;
    }

    String string(String key, String fallback) {
        String value = nullableString(key);
        return value == null ? fallback : value;
    }

    String nullableString(String key) {
        Object value = table.take(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String string)) {
            throw table.failure(key, "expected a string");
        }
        return EnvironmentExpander.expand(string, table.context(), table.childPath(key));
    }

    boolean bool(String key, boolean fallback) {
        Boolean value = nullableBoolean(key);
        return value == null ? fallback : value;
    }

    Boolean nullableBoolean(String key) {
        Object value = table.take(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Boolean flag)) {
            throw table.failure(key, "expected true or false");
        }
        return flag;
    }

    int integer(String key, int fallback) {
        Integer value = nullableInteger(key);
        return value == null ? fallback : value;
    }

    Integer nullableInteger(String key) {
        Object value = table.take(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Long number)) {
            throw table.failure(key, "expected an integer");
        }
        try {
            return Math.toIntExact(number);
        } catch (ArithmeticException exception) {
            throw table.failure(key, "integer is outside the supported range");
        }
    }

    long longInteger(String key, long fallback) {
        Object value = table.take(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Long number)) {
            throw table.failure(key, "expected an integer");
        }
        return number;
    }

    double number(String key, double fallback) {
        Object value = table.take(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Long integer) {
            return integer.doubleValue();
        }
        if (value instanceof Double decimal) {
            return decimal;
        }
        throw table.failure(key, "expected a number");
    }

    Level level(String key, Level fallback) {
        String value = nullableString(key);
        return value == null ? fallback : ConfigurationCompiler.parseLevel(value, table.context(), table.childPath(key));
    }

    Level nullableLevel(String key) {
        String value = nullableString(key);
        return value == null ? null : ConfigurationCompiler.parseLevel(value, table.context(), table.childPath(key));
    }

    Duration duration(String key, Duration fallback) {
        String value = nullableString(key);
        if (value == null) {
            return fallback;
        }
        try {
            return DurationParser.parse(value);
        } catch (RuntimeException exception) {
            throw table.failure(key, exception.getMessage());
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
            throw table.failure(key, exception.getMessage());
        }
    }

    int sizeAsInt(String key, int fallback) {
        long value = size(key, fallback);
        if (value > Integer.MAX_VALUE) {
            throw table.failure(key, "must be at most " + Integer.MAX_VALUE + " bytes");
        }
        return (int) value;
    }

    List<String> stringList(String key, List<String> fallback) {
        List<String> value = nullableStringList(key);
        return value == null ? fallback : value;
    }

    List<String> nullableStringList(String key) {
        Object value = table.take(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw table.failure(key, "expected an array of strings");
        }
        if (list.size() > ContextConfig.MAX_ALLOWLIST_ENTRIES) {
            throw table.failure(key, "contains too many values");
        }

        List<String> result = new ArrayList<>(list.size());
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < list.size(); index++) {
            Object item = list.get(index);
            if (!(item instanceof String text)) {
                throw table.failure(key + "[" + index + "]", "expected a string");
            }
            String path = table.childPath(key + "[" + index + "]");
            String expanded = EnvironmentExpander.expand(text, table.context(), path);
            if (!seen.add(expanded)) {
                throw table.failure(key, "contains duplicate value '" + expanded + "'");
            }
            result.add(expanded);
        }
        return List.copyOf(result);
    }
}
