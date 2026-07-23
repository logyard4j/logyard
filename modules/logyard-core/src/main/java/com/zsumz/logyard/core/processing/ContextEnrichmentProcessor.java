package com.zsumz.logyard.core.processing;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.ContextProvider;
import com.zsumz.logyard.api.spi.EventProcessor;
import com.zsumz.logyard.core.diagnostics.FailureIsolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Merges bounded ServiceLoader context snapshots on the enabled caller path. */
public final class ContextEnrichmentProcessor implements EventProcessor {
    private final List<ProviderBinding> providers;
    private final List<String> includedKeys;

    public ContextEnrichmentProcessor(
            List<ContextProvider> providers,
            List<String> includedKeys) {
        Objects.requireNonNull(providers, "providers");
        List<ProviderBinding> bindings = new ArrayList<>(providers.size());
        for (ContextProvider provider : providers) {
            ContextProvider current = Objects.requireNonNull(provider, "context provider");
            bindings.add(new ProviderBinding(current, providerName(current)));
        }
        this.providers = List.copyOf(bindings);
        this.includedKeys = List.copyOf(Objects.requireNonNull(includedKeys, "includedKeys"));
    }

    @Override
    public LogEvent process(LogEvent event) {
        Objects.requireNonNull(event, "event");
        AttributeSet.Builder captured = AttributeSet.builder();
        int failures = 0;
        for (ProviderBinding binding : providers) {
            try {
                AttributeSet values = binding.provider().capture(includedKeys);
                if (values != null) {
                    captured.putAll(values);
                }
            } catch (Throwable failure) {
                FailureIsolation.rethrowIfFatal(failure);
                FailureIsolation.restoreInterrupt(failure);
                failures++;
                captured.put(
                        "logyard.context.failure." + binding.name(),
                        failure.getClass().getName());
            }
        }
        if (failures > 0) {
            captured.put("logyard.context.capture_failures", failures);
        }
        AttributeSet enrichment = captured.build();
        return enrichment.isEmpty()
                ? event
                : event.withAttributes(event.attributes().mergedWith(enrichment));
    }

    private static String providerName(ContextProvider provider) {
        try {
            String name = provider.name();
            if (name != null && name.matches("[A-Za-z0-9_.-]{1,96}")) {
                return name;
            }
        } catch (Throwable failure) {
            FailureIsolation.rethrowIfFatal(failure);
            FailureIsolation.restoreInterrupt(failure);
            // The stable class name below is enough to identify a hostile provider.
        }
        String className = provider.getClass().getName();
        int separator = className.lastIndexOf('.');
        String simpleName = separator < 0 ? className : className.substring(separator + 1);
        String normalized = simpleName.replaceAll("[^A-Za-z0-9_.-]", "-");
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }

    private record ProviderBinding(ContextProvider provider, String name) {
    }
}
