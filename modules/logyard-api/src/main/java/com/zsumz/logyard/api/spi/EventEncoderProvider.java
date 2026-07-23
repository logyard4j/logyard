package com.zsumz.logyard.api.spi;

/**
 * ServiceLoader extension point for named event encoders.
 *
 * <p>The provider name is the stable configuration identity. Logyard validates the
 * exact configuration-key contract before creating an encoder owned by one
 * immutable runtime plan.</p>
 */
public interface EventEncoderProvider {
    String name();

    default ProviderConfigurationSpec configurationSpec() {
        return ProviderConfigurationSpec.none();
    }

    EventEncoder create(ProviderConfiguration configuration);
}
