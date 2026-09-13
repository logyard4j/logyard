package com.logyard4j.logyard.config.output;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.config.delivery.DeliveryOverrideConfig;
import com.logyard4j.logyard.config.extension.ProviderReferenceConfig;
import com.logyard4j.logyard.config.validation.ConfigNames;
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
