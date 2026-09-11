package com.logyard4j.config.encoding;

import com.logyard4j.config.extension.ProviderReferenceConfig;
import com.logyard4j.config.validation.ConfigNames;
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
