package com.logyard4j.runtime.extension;

import com.logyard4j.api.spi.encoding.EventEncoderProvider;
import com.logyard4j.api.spi.processing.EventProcessorProvider;
import com.logyard4j.api.spi.output.OutputProvider;
import com.logyard4j.api.spi.formatting.TextFormatterProvider;
import com.logyard4j.runtime.extension.discovery.EventEncoderProviderDiscovery;
import com.logyard4j.runtime.extension.discovery.EventProcessorProviderDiscovery;
import com.logyard4j.runtime.extension.discovery.OutputProviderDiscovery;
import com.logyard4j.runtime.extension.discovery.TextFormatterProviderDiscovery;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable registry of every discovered runtime extension.
 *
 * @param formatters text formatter providers keyed by configuration name
 * @param encoders event encoder providers keyed by configuration name
 * @param outputs custom output providers keyed by configuration name
 * @param processors filter and enricher providers keyed by configuration name
 */
public record ExtensionRegistry(
        Map<String, TextFormatterProvider> formatters,
        Map<String, EventEncoderProvider> encoders,
        Map<String, OutputProvider> outputs,
        Map<String, EventProcessorProvider> processors) {

    /** Creates a defensive, immutable snapshot of provider maps. */
    public ExtensionRegistry {
        formatters = immutableCopy(formatters, "formatters");
        encoders = immutableCopy(encoders, "encoders");
        outputs = immutableCopy(outputs, "outputs");
        processors = immutableCopy(processors, "processors");
    }

    /** Discovers all supported extension types using their bounded deterministic registries. */
    public static ExtensionRegistry discover() {
        return new ExtensionRegistry(
                TextFormatterProviderDiscovery.discover(),
                EventEncoderProviderDiscovery.discover(),
                OutputProviderDiscovery.discover(),
                EventProcessorProviderDiscovery.discover());
    }

    private static <T> Map<String, T> immutableCopy(Map<String, T> providers, String name) {
        return Map.copyOf(Objects.requireNonNull(providers, name));
    }
}
