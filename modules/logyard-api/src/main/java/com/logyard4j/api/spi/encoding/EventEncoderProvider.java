package com.logyard4j.api.spi.encoding;

import com.logyard4j.api.spi.config.ProviderConfiguration;
import com.logyard4j.api.spi.config.ProviderConfigurationSpec;

/**
 * ServiceLoader extension point for named event encoders.
 *
 * <p>The provider name is the stable configuration identity. Logyard validates the
 * exact configuration-key contract before creating an encoder owned by one
 * immutable runtime plan.</p>
 */
public interface EventEncoderProvider {
    /**
     * Returns the stable configuration name of this provider.
     *
     * @return provider name
     */
    String name();

    /**
     * Returns the exact configuration-key contract.
     *
     * @return provider configuration specification
     */
    default ProviderConfigurationSpec configurationSpec() {
        return ProviderConfigurationSpec.none();
    }

    /**
     * Creates an encoder owned by one immutable runtime plan.
     *
     * @param configuration validated provider configuration
     * @return new encoder
     */
    EventEncoder create(ProviderConfiguration configuration);
}
