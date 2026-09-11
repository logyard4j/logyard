package com.logyard4j.config.processing;

import com.logyard4j.config.extension.ProviderReferenceConfig;
import com.logyard4j.config.validation.ConfigNames;
import java.util.Objects;

/** Provider-backed filter instance. */
public record ProviderFilterConfig(
        String name,
        ProviderReferenceConfig providerReference) implements FilterConfig {
    public ProviderFilterConfig {
        name = ConfigNames.component(name, "filter name");
        if (name.startsWith("logyard-")) {
            throw new IllegalArgumentException("filter names beginning with 'logyard-' are reserved");
        }
        Objects.requireNonNull(providerReference, "providerReference");
    }
}
