package com.zsumz.logyard.runtime.output;

import com.zsumz.logyard.api.spi.output.OutputProvider;
import com.zsumz.logyard.runtime.extension.NamedProviderDiscovery;

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
