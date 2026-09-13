package com.logyard4j.logyard.runtime.extension.discovery;

import com.logyard4j.logyard.api.spi.encoding.EventEncoderProvider;
import java.util.Map;

/** Deterministic, bounded discovery of custom event-encoder providers. */
public final class EventEncoderProviderDiscovery {
    public static final int MAX_PROVIDERS = NamedProviderDiscovery.MAX_PROVIDERS;

    private EventEncoderProviderDiscovery() {
    }

    public static Map<String, EventEncoderProvider> discover() {
        return discover(NamedProviderDiscovery.contextLoader(EventEncoderProviderDiscovery.class));
    }

    public static Map<String, EventEncoderProvider> discover(ClassLoader loader) {
        return NamedProviderDiscovery.discover(
                EventEncoderProvider.class, loader, EventEncoderProvider::name, "event encoder");
    }
}
