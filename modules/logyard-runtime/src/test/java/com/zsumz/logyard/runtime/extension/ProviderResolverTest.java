package com.zsumz.logyard.runtime.extension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.spi.processing.EventProcessor;
import com.zsumz.logyard.api.spi.processing.EventProcessorKind;
import com.zsumz.logyard.api.spi.processing.EventProcessorProvider;
import com.zsumz.logyard.api.spi.config.ProviderConfiguration;
import com.zsumz.logyard.api.spi.config.ProviderConfigurationSpec;
import com.zsumz.logyard.api.spi.formatting.TextFormatter;
import com.zsumz.logyard.api.spi.formatting.TextFormatterProvider;
import com.zsumz.logyard.config.extension.ProviderReferenceConfig;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ProviderResolverTest {
    @Test
    void resolvesProviderWithMatchingImplementationAndConfiguration() {
        TextFormatterProvider provider = new TestFormatterProvider();
        ProviderReferenceConfig reference = new ProviderReferenceConfig(
                provider.name(),
                provider.getClass().getName(),
                new ProviderConfiguration(Map.of("pattern", "compact")));

        TextFormatterProvider resolved =
                ProviderResolver.resolve(Map.of(provider.name(), provider), reference, "formatter 'test'", TextFormatterProvider::configurationSpec);

        assertSame(provider, resolved);
    }

    @Test
    void rejectsUnavailableAndMismatchedImplementations() {
        TextFormatterProvider provider = new TestFormatterProvider();
        ProviderReferenceConfig unavailable = new ProviderReferenceConfig("missing", null, ProviderConfiguration.EMPTY);
        ProviderReferenceConfig mismatched =
                new ProviderReferenceConfig(provider.name(), String.class.getName(), ProviderConfiguration.EMPTY);

        IllegalArgumentException missingFailure = assertThrows(
                IllegalArgumentException.class,
                () -> ProviderResolver.resolve(Map.of(), unavailable, "formatter 'missing'", TextFormatterProvider::configurationSpec));
        IllegalArgumentException pinFailure = assertThrows(
                IllegalArgumentException.class,
                () -> ProviderResolver.resolve(Map.of(provider.name(), provider), mismatched, "formatter 'test'", TextFormatterProvider::configurationSpec));

        assertTrue(missingFailure.getMessage().contains("unavailable provider 'missing'"));
        assertTrue(pinFailure.getMessage().contains("pins implementation 'java.lang.String'"));
    }

    @Test
    void qualifiesProviderConfigurationFailures() {
        TextFormatterProvider provider = new TestFormatterProvider();
        ProviderReferenceConfig reference =
                new ProviderReferenceConfig(provider.name(), null, new ProviderConfiguration(Map.of("unknown", true)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ProviderResolver.resolve(Map.of(provider.name(), provider), reference, "formatter 'test'", TextFormatterProvider::configurationSpec));

        assertTrue(failure.getMessage().startsWith("formatter 'test': unknown provider configuration key"));
    }

    @Test
    void rejectsProcessorProvidersWithTheWrongRole() {
        EventProcessorProvider provider = new TestProcessorProvider();
        ProviderReferenceConfig reference = new ProviderReferenceConfig(provider.name(), null, ProviderConfiguration.EMPTY);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ProviderResolver.resolveProcessor(Map.of(provider.name(), provider), reference, EventProcessorKind.ENRICHER, "enricher 'test'"));

        assertTrue(failure.getMessage().contains("declared as filter"));
    }

    private static final class TestFormatterProvider implements TextFormatterProvider {
        @Override public String name() { return "test"; }

        @Override
        public ProviderConfigurationSpec configurationSpec() {
            return ProviderConfigurationSpec.of(Set.of("pattern"), Set.of());
        }

        @Override public TextFormatter create(ProviderConfiguration configuration) { return event -> "test"; }
    }

    private static final class TestProcessorProvider implements EventProcessorProvider {
        @Override public String name() { return "test"; }
        @Override public EventProcessorKind kind() { return EventProcessorKind.FILTER; }
        @Override public EventProcessor create(ProviderConfiguration configuration) { return event -> event; }
    }
}
