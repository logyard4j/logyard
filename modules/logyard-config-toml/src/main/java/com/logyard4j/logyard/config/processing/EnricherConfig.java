package com.logyard4j.logyard.config.processing;

import com.logyard4j.logyard.config.extension.ProviderReferenceConfig;
import com.logyard4j.logyard.config.validation.ConfigNames;
import java.util.Objects;

/** Explicitly named provider-backed enricher instance. */
public record EnricherConfig(String name, ProviderReferenceConfig providerReference) {
    public EnricherConfig {
        name = ConfigNames.component(name, "enricher name");
        if (name.startsWith("logyard-")) {
            throw new IllegalArgumentException("enricher names beginning with 'logyard-' are reserved");
        }
        Objects.requireNonNull(providerReference, "providerReference");
    }
}
