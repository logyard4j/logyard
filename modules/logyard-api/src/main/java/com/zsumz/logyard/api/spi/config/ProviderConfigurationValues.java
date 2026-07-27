package com.zsumz.logyard.api.spi.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validates and immutably copies the flattened scalar values accepted by provider configuration. */
final class ProviderConfigurationValues {
    private ProviderConfigurationValues() {
    }

    static Map<String, Object> copyOf(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        if (values.size() > ProviderConfiguration.MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "provider configuration exceeds " + ProviderConfiguration.MAX_ENTRIES + " entries");
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String normalized = normalizeKey(key);
            Object existing = copy.putIfAbsent(normalized, copyValue(value, normalized));
            if (existing != null) {
                throw new IllegalArgumentException(
                        "duplicate normalized provider configuration key: " + normalized);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    static String normalizeKey(String key) {
        String normalized = Objects.requireNonNull(key, "provider configuration key").trim();
        if (normalized.isEmpty() || normalized.length() > ProviderConfiguration.MAX_KEY_CHARS
                || !normalized.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("invalid provider configuration key: " + normalized);
        }
        return normalized;
    }

    static IllegalArgumentException typeFailure(String key, String expected, Object actual) {
        return new IllegalArgumentException(
                "provider configuration key '" + key + "' must be a " + expected
                        + ", not " + actual.getClass().getSimpleName());
    }

    private static Object copyValue(Object value, String key) {
        Objects.requireNonNull(value, "provider configuration value for " + key);
        if (value instanceof String text) {
            if (text.length() > ProviderConfiguration.MAX_TEXT_CHARS) {
                throw new IllegalArgumentException(
                        "provider configuration value exceeds " + ProviderConfiguration.MAX_TEXT_CHARS + " characters: " + key);
            }
            return text;
        }
        if (value instanceof Long || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("provider configuration number must be finite: " + key);
            }
            return number;
        }
        if (value instanceof List<?> list) {
            if (list.size() > ProviderConfiguration.MAX_LIST_ITEMS) {
                throw new IllegalArgumentException(
                        "provider configuration array exceeds " + ProviderConfiguration.MAX_LIST_ITEMS + " items: " + key);
            }
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof List<?> || item instanceof Map<?, ?>) {
                    throw new IllegalArgumentException(
                            "provider configuration arrays must contain scalar values: " + key);
                }
                copy.add(copyValue(item, key));
            }
            return List.copyOf(copy);
        }
        throw new IllegalArgumentException(
                "provider configuration value has unsupported type for '" + key + "': "
                        + value.getClass().getName());
    }
}
