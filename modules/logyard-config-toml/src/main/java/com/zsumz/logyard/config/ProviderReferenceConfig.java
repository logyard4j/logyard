package com.zsumz.logyard.config;

import com.zsumz.logyard.api.spi.config.ProviderConfiguration;

import java.util.Objects;

/** Explicit provider identity, optional implementation pin, and bounded configuration. */
public record ProviderReferenceConfig(
        String provider,
        String implementation,
        ProviderConfiguration configuration) {
    public ProviderReferenceConfig {
        provider = ConfigNames.provider(provider);
        implementation = normalizeImplementation(implementation);
        configuration = Objects.requireNonNullElse(configuration, ProviderConfiguration.EMPTY);
    }

    private static String normalizeImplementation(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()
                || normalized.length() > 256
                || !normalized.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) {
            throw new IllegalArgumentException("invalid provider implementation class: " + normalized);
        }
        return normalized;
    }
}
