package com.logyard4j.api.spi.config;

import java.util.List;
import java.util.Map;

/** Immutable, flattened, bounded configuration supplied to one extension provider. */
public final class ProviderConfiguration {
    /** Maximum number of configuration entries. */
    public static final int MAX_ENTRIES = 64;

    /** Maximum UTF-16 characters in a normalized key. */
    public static final int MAX_KEY_CHARS = 128;

    /** Maximum UTF-16 characters in a text value. */
    public static final int MAX_TEXT_CHARS = 4_096;

    /** Maximum scalar values in a list. */
    public static final int MAX_LIST_ITEMS = 128;

    /** Shared empty provider configuration. */
    public static final ProviderConfiguration EMPTY = new ProviderConfiguration(Map.of());

    private final Map<String, Object> values;

    /**
     * Creates a validated immutable configuration.
     *
     * @param values flattened scalar and scalar-list values
     */
    public ProviderConfiguration(Map<String, ?> values) {
        this.values = ProviderConfigurationValues.copyOf(values);
    }

    /**
     * Returns the immutable configuration values keyed by normalized name.
     *
     * @return immutable configuration map
     */
    public Map<String, Object> values() {
        return values;
    }

    /**
     * Returns whether a key is configured.
     *
     * @param key normalized key
     * @return {@code true} when the key is present
     */
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    /**
     * Returns an untyped value.
     *
     * @param key normalized key
     * @return configured value, or {@code null}
     */
    public Object value(String key) {
        return values.get(key);
    }

    /**
     * Returns a text value.
     *
     * @param key normalized key
     * @return configured text, or {@code null}
     * @throws IllegalArgumentException if the configured value is not text
     */
    public String string(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw ProviderConfigurationValues.typeFailure(key, "string", value);
    }

    /**
     * Returns a required non-blank text value.
     *
     * @param key normalized key
     * @return configured non-blank text
     * @throws IllegalArgumentException if the value is absent, blank, or not text
     */
    public String requiredString(String key) {
        String value = string(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "provider configuration key '" + key + "' must be a non-blank string");
        }
        return value;
    }

    /**
     * Returns an integer value.
     *
     * @param key normalized key
     * @return configured integer, or {@code null}
     * @throws IllegalArgumentException if the configured value is not an integer
     */
    public Long longValue(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Long number) {
            return number;
        }
        throw ProviderConfigurationValues.typeFailure(key, "integer", value);
    }

    /**
     * Returns a numeric value.
     *
     * @param key normalized key
     * @return configured number, or {@code null}
     * @throws IllegalArgumentException if the configured value is not numeric
     */
    public Double doubleValue(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Double number) {
            return number;
        }
        if (value instanceof Long number) {
            return number.doubleValue();
        }
        throw ProviderConfigurationValues.typeFailure(key, "number", value);
    }

    /**
     * Returns a boolean value.
     *
     * @param key normalized key
     * @return configured flag, or {@code null}
     * @throws IllegalArgumentException if the configured value is not a boolean
     */
    public Boolean booleanValue(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw ProviderConfigurationValues.typeFailure(key, "boolean", value);
    }

    /**
     * Returns a scalar list.
     *
     * @param key normalized key
     * @return immutable configured list, or {@code null}
     * @throws IllegalArgumentException if the configured value is not a list
     */
    public List<?> list(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list;
        }
        throw ProviderConfigurationValues.typeFailure(key, "array", value);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ProviderConfiguration configuration
                && values.equals(configuration.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    /** Omits values so secrets are not exposed by diagnostics or enclosing record strings. */
    @Override
    public String toString() {
        return "ProviderConfiguration[keys=" + values.keySet() + "]";
    }

}
