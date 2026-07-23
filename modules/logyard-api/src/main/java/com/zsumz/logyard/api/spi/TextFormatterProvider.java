package com.zsumz.logyard.api.spi;

/**
 * ServiceLoader extension point for named text formatters.
 *
 * <p>The provider name is the stable configuration identity. Logyard validates the
 * exact configuration-key contract before calling {@link #create(ProviderConfiguration)}.
 * The returned formatter is owned by one runtime plan.</p>
 */
public interface TextFormatterProvider {
    String name();

    default ProviderConfigurationSpec configurationSpec() {
        return ProviderConfigurationSpec.none();
    }

    TextFormatter create(ProviderConfiguration configuration);
}
