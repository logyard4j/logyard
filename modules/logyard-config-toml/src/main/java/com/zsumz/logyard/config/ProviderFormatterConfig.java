package com.zsumz.logyard.config;

import java.util.Objects;

/** Provider-backed formatter definition. */
public record ProviderFormatterConfig(
        String name,
        ProviderReferenceConfig providerReference) implements FormatterConfig {
    public ProviderFormatterConfig {
        name = ConfigNames.component(name, "formatter name");
        Objects.requireNonNull(providerReference, "providerReference");
    }
}
