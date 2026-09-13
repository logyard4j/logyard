package com.logyard4j.logyard.tests.extensions;

import com.logyard4j.logyard.api.spi.encoding.EventEncoder;
import com.logyard4j.logyard.api.spi.encoding.EventEncoderProvider;
import com.logyard4j.logyard.api.spi.config.ProviderConfiguration;
import com.logyard4j.logyard.api.spi.config.ProviderConfigurationSpec;

import java.util.Set;

/** Service-loaded encoder fixture for integration tests. */
public final class TestEncoderProvider implements EventEncoderProvider {
    @Override
    public String name() {
        return "test-encoder";
    }

    @Override
    public ProviderConfigurationSpec configurationSpec() {
        return ProviderConfigurationSpec.of(Set.of("tag"), Set.of("tag"));
    }

    @Override
    public EventEncoder create(ProviderConfiguration configuration) {
        String tag = configuration.requiredString("tag");
        return event -> tag + ':' + event.renderedMessage();
    }
}
