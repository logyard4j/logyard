package com.zsumz.logyard.runtime.format;

import com.zsumz.logyard.api.spi.TextFormatterProvider;
import com.zsumz.logyard.runtime.extension.NamedProviderDiscovery;

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
