package com.zsumz.logyard.runtime.extension.discovery;

import com.zsumz.logyard.api.spi.context.ContextProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** Deterministic, bounded discovery of optional caller-context providers. */
public final class ContextProviderDiscovery {
    public static final int MAX_PROVIDERS = 32;

    private ContextProviderDiscovery() {
    }

    public static List<ContextProvider> discover() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = context == null
                ? ContextProviderDiscovery.class.getClassLoader()
                : context;
        Map<String, ContextProvider> providers = new LinkedHashMap<>();
        try {
            Iterator<ServiceLoader.Provider<ContextProvider>> candidates =
                    ServiceLoader.load(ContextProvider.class, loader).stream().iterator();
            while (candidates.hasNext()) {
                ServiceLoader.Provider<ContextProvider> candidate = candidates.next();
                String type = candidate.type().getName();
                if (providers.containsKey(type)) {
                    continue;
                }
                if (providers.size() >= MAX_PROVIDERS) {
                    throw new IllegalStateException(
                            "more than " + MAX_PROVIDERS + " Logyard context providers are visible");
                }
                providers.put(type, candidate.get());
            }
        } catch (ServiceConfigurationError failure) {
            throw new IllegalStateException("could not load a Logyard context provider", failure);
        }
        List<ContextProvider> result = new ArrayList<>(providers.values());
        result.sort(Comparator.comparing(provider -> provider.getClass().getName()));
        return List.copyOf(result);
    }
}
