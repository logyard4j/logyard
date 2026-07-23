package com.zsumz.logyard.config.formatting;

import com.zsumz.logyard.config.extension.ProviderReferenceConfig;
import com.zsumz.logyard.config.validation.ConfigNames;
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
