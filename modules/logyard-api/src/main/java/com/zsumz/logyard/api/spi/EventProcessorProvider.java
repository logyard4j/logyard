package com.zsumz.logyard.api.spi;

/**
 * ServiceLoader extension point for explicitly configured enrichment and filtering.
 *
 * <p>Providers expose a stable configuration name and create an isolated processor
 * instance for each runtime assembly. Provider implementations must not retain a
 * runtime, output, or application class loader beyond the lifetime of the returned
 * processor.</p>
 */
public interface EventProcessorProvider {
    /** Stable lower-case provider name used by an explicit enricher or filter definition. */
    String name();

    /** Prevents an enricher provider from being wired as a dropping filter, or vice versa. */
    EventProcessorKind kind();

    default ProviderConfigurationSpec configurationSpec() {
        return ProviderConfigurationSpec.none();
    }

    /** Creates a processor owned by one immutable runtime plan. */
    EventProcessor create(ProviderConfiguration configuration);
}
