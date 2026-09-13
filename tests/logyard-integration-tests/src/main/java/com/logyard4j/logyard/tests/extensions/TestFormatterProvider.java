package com.logyard4j.logyard.tests.extensions;

import com.logyard4j.logyard.api.spi.config.ProviderConfiguration;
import com.logyard4j.logyard.api.spi.config.ProviderConfigurationSpec;
import com.logyard4j.logyard.api.spi.formatting.TextFormatter;
import com.logyard4j.logyard.api.spi.formatting.TextFormatterProvider;

import java.util.Set;

/** Service-loaded formatter fixture for integration tests. */
public final class TestFormatterProvider implements TextFormatterProvider {
    @Override
    public String name() {
        return "test-formatter";
    }

    @Override
    public ProviderConfigurationSpec configurationSpec() {
        return ProviderConfigurationSpec.of(Set.of("prefix"), Set.of("prefix"));
    }

    @Override
    public TextFormatter create(ProviderConfiguration configuration) {
        String prefix = configuration.requiredString("prefix");
        return event -> prefix + '\n' + event.renderedMessage();
    }
}
