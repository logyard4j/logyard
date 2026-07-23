package com.zsumz.logyard.config;

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
