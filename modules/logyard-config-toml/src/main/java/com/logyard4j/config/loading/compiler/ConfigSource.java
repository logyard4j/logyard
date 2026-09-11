package com.logyard4j.config.loading.compiler;

import com.logyard4j.config.ConfigurationException;
import com.logyard4j.config.loading.result.ConfigLocations;
import java.util.Map;
import java.util.Objects;

/** Shared source identity, environment, and value origins for one configuration decode. */
record ConfigSource(String name, Map<String, String> environment, ConfigLocations locations) {
    ConfigSource {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(locations, "locations");
    }

    /** Builds the located diagnostic for one configuration path. */
    ConfigurationException failure(String path, String message) {
        return new ConfigurationException(locate(path) + ": " + path + ": " + message);
    }

    /** Locates a component failure once, keeping an already-located diagnostic intact. */
    ConfigurationException failureFrom(String path, IllegalArgumentException failure) {
        return failure instanceof ConfigurationException located ? located : failure(path, failure.getMessage());
    }

    /** Returns the origin of the value at one path, or the source name when unknown. */
    String locate(String path) {
        String origin = locations.describe(path);
        return origin != null ? origin : name;
    }
}
