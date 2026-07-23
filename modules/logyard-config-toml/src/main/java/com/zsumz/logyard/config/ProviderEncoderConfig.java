package com.zsumz.logyard.config;

import java.util.Objects;

/** Provider-backed event encoder definition. */
public record ProviderEncoderConfig(
        String name,
        ProviderReferenceConfig providerReference) implements EncoderConfig {
    public ProviderEncoderConfig {
        name = ConfigNames.component(name, "encoder name");
        Objects.requireNonNull(providerReference, "providerReference");
    }
}
