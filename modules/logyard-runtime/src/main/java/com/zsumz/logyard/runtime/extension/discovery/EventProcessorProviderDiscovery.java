package com.zsumz.logyard.runtime.extension.discovery;

import com.zsumz.logyard.api.spi.processing.EventProcessorProvider;
import java.util.Map;

/** Deterministic, bounded discovery of custom enricher and filter providers. */
public final class EventProcessorProviderDiscovery {
    public static final int MAX_PROVIDERS = NamedProviderDiscovery.MAX_PROVIDERS;

    private EventProcessorProviderDiscovery() {
    }

    public static Map<String, EventProcessorProvider> discover() {
        return discover(NamedProviderDiscovery.contextLoader(EventProcessorProviderDiscovery.class));
    }

    public static Map<String, EventProcessorProvider> discover(ClassLoader loader) {
        return NamedProviderDiscovery.discover(
                EventProcessorProvider.class,
                loader,
                EventProcessorProvider::name,
                "event processor");
    }
}
