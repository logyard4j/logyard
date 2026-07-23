package com.zsumz.logyard.api.spi;

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
    String name();

    default ProviderConfigurationSpec configurationSpec() {
        return ProviderConfigurationSpec.none();
    }

    EventSink create(OutputProviderContext context, ProviderConfiguration configuration);
}
