package com.zsumz.logyard.config;

import com.zsumz.logyard.api.spi.config.ProviderConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Decodes a provider identity and its bounded flattened configuration. */
final class ProviderReferenceDecoder {
    private ProviderReferenceDecoder() {
    }

    static ProviderReferenceConfig decode(ConfigReader reader) {
        String provider = reader.requiredString("provider");
        String implementation = reader.nullableString("implementation");
        ProviderConfiguration configuration = configuration(reader.dynamicObject("config"), reader);
        try {
            return new ProviderReferenceConfig(provider, implementation, configuration);
        } catch (IllegalArgumentException exception) {
            throw reader.failure("provider", exception.getMessage());
        }
    }

    private static ProviderConfiguration configuration(Map<String, Object> raw, ConfigReader reader) {
        LinkedHashMap<String, Object> flattened = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            flatten(entry.getKey(), entry.getValue(), flattened, reader, "config." + entry.getKey(), 1);
        }
        try {
            return flattened.isEmpty() ? ProviderConfiguration.EMPTY : new ProviderConfiguration(flattened);
        } catch (IllegalArgumentException exception) {
            throw reader.failure("config", exception.getMessage());
        }
    }

    private static void flatten(
            String key,
            Object value,
            Map<String, Object> flattened,
            ConfigReader reader,
            String path,
            int depth) {
        if (depth > 4) {
            throw reader.failure(path, "provider configuration nesting exceeds four levels");
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                throw reader.failure(path, "provider configuration table must not be empty");
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String child)) {
                    throw reader.failure(path, "provider configuration key is not a string");
                }
                flatten(key + "." + child, entry.getValue(), flattened, reader, path + "." + child, depth + 1);
            }
            return;
        }

        Object normalized = normalize(value, reader, path);
        if (flattened.putIfAbsent(key, normalized) != null) {
            throw reader.failure(path, "duplicate flattened provider configuration key '" + key + "'");
        }
        if (flattened.size() > ProviderConfiguration.MAX_ENTRIES) {
            throw reader.failure("config", "provider configuration exceeds " + ProviderConfiguration.MAX_ENTRIES + " entries");
        }
    }

    private static Object normalize(Object value, ConfigReader reader, String path) {
        if (value instanceof String text) {
            return EnvironmentExpander.expand(text, reader.environment(), reader.source(), reader.childPath(path));
        }
        if (value instanceof Long || value instanceof Double || value instanceof Boolean) {
            return value;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                Object item = list.get(index);
                if (item instanceof Map<?, ?> || item instanceof List<?>) {
                    throw reader.failure(path + "[" + index + "]", "provider configuration arrays must contain scalar values");
                }
                copy.add(normalize(item, reader, path + "[" + index + "]"));
            }
            return List.copyOf(copy);
        }
        throw reader.failure(path, "provider configuration values must be scalar or scalar arrays");
    }
}
