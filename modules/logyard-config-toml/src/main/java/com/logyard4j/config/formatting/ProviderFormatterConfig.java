package com.logyard4j.config.formatting;

import com.logyard4j.config.extension.ProviderReferenceConfig;
import com.logyard4j.config.validation.ConfigNames;
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
