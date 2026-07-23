package com.zsumz.logyard.tests.extensions;

import com.zsumz.logyard.api.spi.ProviderConfiguration;
import com.zsumz.logyard.api.spi.ProviderConfigurationSpec;
import com.zsumz.logyard.api.spi.TextFormatter;
import com.zsumz.logyard.api.spi.TextFormatterProvider;

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
