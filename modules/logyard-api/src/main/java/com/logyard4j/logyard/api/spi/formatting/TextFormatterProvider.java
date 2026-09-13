package com.logyard4j.logyard.api.spi.formatting;

import com.logyard4j.logyard.api.spi.config.ProviderConfiguration;
import com.logyard4j.logyard.api.spi.config.ProviderConfigurationSpec;

/**
 * ServiceLoader extension point for named text formatters.
 *
 * <p>The provider name is the stable configuration identity. Logyard validates the
 * exact configuration-key contract before calling {@link #create(ProviderConfiguration)}.
 * The returned formatter is owned by one runtime plan.</p>
 */
public interface TextFormatterProvider {
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
     * Creates a formatter owned by one immutable runtime plan.
     *
     * @param configuration validated provider configuration
     * @return new formatter
     */
    TextFormatter create(ProviderConfiguration configuration);
}
