package com.zsumz.logyard.runtime.extension.discovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Function;

/** Shared deterministic and bounded ServiceLoader catalog construction. */
final class NamedProviderDiscovery {
    static final int MAX_PROVIDERS = 64;
    private static final String NAME_PATTERN = "[a-z][a-z0-9_.-]{0,63}";

    private NamedProviderDiscovery() {
    }

    static <T> Map<String, T> discover(
            Class<T> service,
            ClassLoader loader,
            Function<T, String> name,
            String label) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(label, "label");
        List<T> candidates = new ArrayList<>();
        try {
            Iterator<ServiceLoader.Provider<T>> iterator =
                    ServiceLoader.load(service, loader).stream().iterator();
            while (iterator.hasNext()) {
                if (candidates.size() >= MAX_PROVIDERS) {
                    throw new IllegalStateException(
                            "more than " + MAX_PROVIDERS + " Logyard " + label + " providers are visible");
                }
                candidates.add(Objects.requireNonNull(
                        iterator.next().get(), label + " provider instance"));
            }
        } catch (ServiceConfigurationError failure) {
            throw new IllegalStateException("could not load a Logyard " + label + " provider", failure);
        }
        candidates.sort(Comparator.comparing(candidate -> candidate.getClass().getName()));
        LinkedHashMap<String, T> result = new LinkedHashMap<>();
        for (T provider : candidates) {
            String providerName = Objects.requireNonNull(
                            name.apply(provider), label + " provider name")
                    .trim()
                    .toLowerCase(Locale.ROOT);
            if (!providerName.matches(NAME_PATTERN)) {
                throw new IllegalStateException(
                        "invalid Logyard " + label + " provider name '" + providerName + "' from "
                                + provider.getClass().getName());
            }
            T existing = result.putIfAbsent(providerName, provider);
            if (existing != null) {
                throw new IllegalStateException(
                        "duplicate Logyard " + label + " provider name '" + providerName + "' from "
                                + existing.getClass().getName() + " and "
                                + provider.getClass().getName());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    static ClassLoader contextLoader(Class<?> anchor) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context == null ? anchor.getClassLoader() : context;
    }
}
