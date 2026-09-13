package com.logyard4j.logyard.config.formatting;

import com.logyard4j.logyard.config.extension.ProviderReferenceConfig;
import com.logyard4j.logyard.config.validation.ConfigNames;
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
