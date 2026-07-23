package com.zsumz.logyard.tests.extensions;

import com.zsumz.logyard.api.spi.processing.EventProcessor;
import com.zsumz.logyard.api.spi.processing.EventProcessorKind;
import com.zsumz.logyard.api.spi.processing.EventProcessorProvider;
import com.zsumz.logyard.api.spi.config.ProviderConfiguration;
import com.zsumz.logyard.api.spi.config.ProviderConfigurationSpec;

import java.util.Set;

/** Service-loaded filter fixture for integration tests. */
public final class TestFilterProvider implements EventProcessorProvider {
    @Override
    public String name() {
        return "test-filter";
    }

    @Override
    public EventProcessorKind kind() {
        return EventProcessorKind.FILTER;
    }

    @Override
    public ProviderConfigurationSpec configurationSpec() {
        return ProviderConfigurationSpec.of(Set.of("allow"), Set.of("allow"));
    }

    @Override
    public EventProcessor create(ProviderConfiguration configuration) {
        boolean allow = Boolean.TRUE.equals(configuration.booleanValue("allow"));
        return event -> allow ? event : null;
    }
}
