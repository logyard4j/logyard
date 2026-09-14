package com.logyard4j.logyard.config.extension;

import com.logyard4j.logyard.api.spi.config.ProviderConfiguration;
import com.logyard4j.logyard.config.validation.ConfigNames;
import java.util.Objects;

/** Explicit provider identity, optional implementation pin, and bounded configuration. */
public record ProviderReferenceConfig(
        String provider,
        String implementation,
        ProviderConfiguration configuration) {
    /** Maximum UTF-16 characters in an implementation class name after trimming. */
    public static final int MAX_IMPLEMENTATION_CHARS = 256;

    public ProviderReferenceConfig {
        provider = ConfigNames.provider(provider);
        implementation = normalizeImplementation(implementation);
        configuration = Objects.requireNonNullElse(configuration, ProviderConfiguration.EMPTY);
    }

    private static String normalizeImplementation(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) <= ' ') start++;
        while (start < end && value.charAt(end - 1) <= ' ') end--;
        if (end - start > MAX_IMPLEMENTATION_CHARS) {
            throw new IllegalArgumentException(
                    "provider implementation class exceeds " + MAX_IMPLEMENTATION_CHARS + " characters after trimming");
        }
        String normalized = value.substring(start, end);
        if (normalized.isEmpty()
                || !normalized.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) {
            throw new IllegalArgumentException("invalid provider implementation class: " + normalized);
        }
        return normalized;
    }
}
