package com.logyard4j.logyard.api.spi.output;

import com.logyard4j.logyard.api.spi.config.ProviderConfiguration;
import com.logyard4j.logyard.api.spi.config.ProviderConfigurationSpec;

/**
 * ServiceLoader extension point for named custom outputs.
 *
 * <p>Logyard validates provider identity, optional implementation pinning, and the
 * exact configuration-key contract before creation. The returned sink is always
 * placed behind a finite Logyard-owned asynchronous worker with caller-thread
 * delivery disabled. The sink owns only its transport/resource lifecycle; it
 * must not create an unbounded queue or retain the provider context after close.</p>
 */
public interface OutputProvider {
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
     * Creates a transport sink owned by one immutable runtime plan.
     *
     * @param context output-scoped runtime resources
     * @param configuration validated provider configuration
     * @return new sink
     * @throws IllegalArgumentException when the candidate configuration is deterministically invalid
     * @throws java.io.UncheckedIOException when a temporary transport or resource condition prevents creation
     */
    EventSink create(OutputProviderContext context, ProviderConfiguration configuration);
}
