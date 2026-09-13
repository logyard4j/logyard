package com.logyard4j.logyard.runtime.extension;

import com.logyard4j.logyard.api.spi.processing.EventProcessorKind;
import com.logyard4j.logyard.api.spi.processing.EventProcessorProvider;
import com.logyard4j.logyard.api.spi.config.ProviderConfigurationSpec;
import com.logyard4j.logyard.config.extension.ProviderReferenceConfig;
import com.logyard4j.logyard.core.failure.ComponentInvocationBoundary;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Resolves and validates explicitly configured extension providers. */
public final class ProviderResolver {
    private ProviderResolver() {
    }

    /**
     * Resolves one provider after validating availability, implementation pinning, and its configuration contract.
     *
     * @param providers discovered providers keyed by configuration name
     * @param reference configured provider reference
     * @param label human-readable configuration location used in failures
     * @param configurationSpec provider-specific configuration contract accessor
     * @param <T> provider type
     * @return the validated provider
     */
    public static <T> T resolve(
            Map<String, T> providers,
            ProviderReferenceConfig reference,
            String label,
            Function<T, ProviderConfigurationSpec> configurationSpec) {
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(configurationSpec, "configurationSpec");

        T provider = providers.get(reference.provider());
        if (provider == null) {
            throw new IllegalArgumentException(label + " references unavailable provider '" + reference.provider() + "'");
        }
        if (reference.implementation() != null && !reference.implementation().equals(provider.getClass().getName())) {
            throw new IllegalArgumentException(
                    label + " pins implementation '" + reference.implementation() + "' but discovered '" + provider.getClass().getName() + "'");
        }
        ProviderConfigurationSpec spec = ComponentInvocationBoundary.call(
                label + " provider configuration contract",
                () -> Objects.requireNonNull(
                        configurationSpec.apply(provider),
                        label + " provider configuration spec"));
        try {
            spec.validate(reference.configuration());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(label + ": " + exception.getMessage(), exception);
        }
        return provider;
    }

    /**
     * Resolves an event processor provider and verifies that its filter/enricher role matches configuration.
     *
     * @param providers discovered processor providers
     * @param reference configured provider reference
     * @param expectedKind required processor role
     * @param label human-readable configuration location used in failures
     * @return the validated processor provider
     */
    public static EventProcessorProvider resolveProcessor(
            Map<String, EventProcessorProvider> providers,
            ProviderReferenceConfig reference,
            EventProcessorKind expectedKind,
            String label) {
        EventProcessorProvider provider = resolve(providers, reference, label, EventProcessorProvider::configurationSpec);
        EventProcessorKind actual = ComponentInvocationBoundary.call(
                label + " provider kind",
                () -> Objects.requireNonNull(provider.kind(), label + " provider kind"));
        if (actual != Objects.requireNonNull(expectedKind, "expectedKind")) {
            throw new IllegalArgumentException(
                    label + " uses provider '" + reference.provider() + "' declared as " + actual.name().toLowerCase(Locale.ROOT));
        }
        return provider;
    }
}
