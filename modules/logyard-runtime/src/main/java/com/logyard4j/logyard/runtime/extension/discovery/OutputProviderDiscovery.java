package com.logyard4j.logyard.runtime.extension.discovery;

import com.logyard4j.logyard.api.spi.output.OutputProvider;
import java.util.Map;

/** Deterministic, bounded discovery of custom output providers. */
public final class OutputProviderDiscovery {
    public static final int MAX_PROVIDERS = NamedProviderDiscovery.MAX_PROVIDERS;

    private OutputProviderDiscovery() {
    }

    public static Map<String, OutputProvider> discover() {
        return discover(NamedProviderDiscovery.contextLoader(OutputProviderDiscovery.class));
    }

    public static Map<String, OutputProvider> discover(ClassLoader loader) {
        return NamedProviderDiscovery.discover(
                OutputProvider.class, loader, OutputProvider::name, "output");
    }
}
