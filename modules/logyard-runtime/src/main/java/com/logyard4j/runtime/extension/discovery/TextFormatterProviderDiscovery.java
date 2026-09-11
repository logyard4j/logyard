package com.logyard4j.runtime.extension.discovery;

import com.logyard4j.api.spi.formatting.TextFormatterProvider;
import java.util.Map;

/** Deterministic, bounded discovery of custom text-formatter providers. */
public final class TextFormatterProviderDiscovery {
    public static final int MAX_PROVIDERS = NamedProviderDiscovery.MAX_PROVIDERS;

    private TextFormatterProviderDiscovery() {
    }

    public static Map<String, TextFormatterProvider> discover() {
        return discover(NamedProviderDiscovery.contextLoader(TextFormatterProviderDiscovery.class));
    }

    public static Map<String, TextFormatterProvider> discover(ClassLoader loader) {
        return NamedProviderDiscovery.discover(
                TextFormatterProvider.class, loader, TextFormatterProvider::name, "text formatter");
    }
}
