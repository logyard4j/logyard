package com.logyard4j.api.spi.processing;

import com.logyard4j.api.spi.config.ProviderConfiguration;
import com.logyard4j.api.spi.config.ProviderConfigurationSpec;

/**
 * ServiceLoader extension point for explicitly configured enrichment and filtering.
 *
 * <p>Providers expose a stable configuration name and create an isolated processor
 * instance for each runtime assembly. Provider implementations must not retain a
 * runtime, output, or application class loader beyond the lifetime of the returned
 * processor.</p>
 */
public interface EventProcessorProvider {
    /**
     * Returns the stable lower-case provider name used by an explicit enricher or filter definition.
     *
     * @return provider name
     */
    String name();

    /**
     * Declares whether this provider creates enrichers or filters.
     *
     * @return processor kind
     */
    EventProcessorKind kind();

    /**
     * Returns the exact configuration-key contract.
     *
     * @return provider configuration specification
     */
    default ProviderConfigurationSpec configurationSpec() {
        return ProviderConfigurationSpec.none();
    }

    /**
     * Creates a processor owned by one immutable runtime plan.
     *
     * @param configuration validated provider configuration
     * @return new processor
     */
    EventProcessor create(ProviderConfiguration configuration);
}
