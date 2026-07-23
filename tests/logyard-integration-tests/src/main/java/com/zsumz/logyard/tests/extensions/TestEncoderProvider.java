package com.zsumz.logyard.tests.extensions;

import com.zsumz.logyard.api.spi.EventEncoder;
import com.zsumz.logyard.api.spi.EventEncoderProvider;
import com.zsumz.logyard.api.spi.ProviderConfiguration;
import com.zsumz.logyard.api.spi.ProviderConfigurationSpec;

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
