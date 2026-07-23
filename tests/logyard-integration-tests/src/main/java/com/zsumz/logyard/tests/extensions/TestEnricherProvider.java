package com.zsumz.logyard.tests.extensions;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.spi.processing.EventProcessor;
import com.zsumz.logyard.api.spi.processing.EventProcessorKind;
import com.zsumz.logyard.api.spi.processing.EventProcessorProvider;
import com.zsumz.logyard.api.spi.config.ProviderConfiguration;
import com.zsumz.logyard.api.spi.config.ProviderConfigurationSpec;

import java.util.Set;

/** Service-loaded enricher fixture for integration tests. */
public final class TestEnricherProvider implements EventProcessorProvider {
    @Override
    public String name() {
        return "test-enricher";
    }

    @Override
    public EventProcessorKind kind() {
        return EventProcessorKind.ENRICHER;
    }

    @Override
    public ProviderConfigurationSpec configurationSpec() {
        return ProviderConfigurationSpec.of(Set.of("attribute"), Set.of("attribute"));
    }

    @Override
    public EventProcessor create(ProviderConfiguration configuration) {
        String attribute = configuration.requiredString("attribute");
        return event -> event.enrich(null, null, AttributeSet.of(attribute, true));
    }
}
