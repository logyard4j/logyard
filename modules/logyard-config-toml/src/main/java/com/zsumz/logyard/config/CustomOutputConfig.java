package com.zsumz.logyard.config;

import com.zsumz.logyard.api.Level;

import java.util.Objects;

/** Explicit provider-backed output. Runtime delivery is always isolated from caller threads. */
public record CustomOutputConfig(
        String name,
        Level minimumLevel,
        ProviderReferenceConfig providerReference,
        String formatter,
        String encoder,
        DeliveryOverrideConfig delivery) implements OutputConfig {
    public CustomOutputConfig {
        name = ConfigNames.component(name, "output name");
        Objects.requireNonNull(minimumLevel, "minimumLevel");
        Objects.requireNonNull(providerReference, "providerReference");
        formatter = ConfigNames.optionalReference(formatter, "formatter reference");
        encoder = ConfigNames.optionalReference(encoder, "encoder reference");
        delivery = delivery == null ? DeliveryOverrideConfig.INHERIT : delivery;
    }
}
