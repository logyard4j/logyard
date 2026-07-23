package com.zsumz.logyard.config.encoding;

import com.zsumz.logyard.config.extension.ProviderReferenceConfig;
import com.zsumz.logyard.config.validation.ConfigNames;
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
