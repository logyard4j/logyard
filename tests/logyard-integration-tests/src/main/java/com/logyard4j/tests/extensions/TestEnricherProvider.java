package com.logyard4j.tests.extensions;

import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.spi.processing.EventProcessor;
import com.logyard4j.api.spi.processing.EventProcessorKind;
import com.logyard4j.api.spi.processing.EventProcessorProvider;
import com.logyard4j.api.spi.config.ProviderConfiguration;
import com.logyard4j.api.spi.config.ProviderConfigurationSpec;

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
