package com.zsumz.logyard.api.spi.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Exact configuration-key contract published by an extension provider.
 *
 * @param allowedKeys complete set of accepted keys
 * @param requiredKeys subset that must be present
 */
public record ProviderConfigurationSpec(Set<String> allowedKeys, Set<String> requiredKeys) {
    private static final ProviderConfigurationSpec NONE =
            new ProviderConfigurationSpec(Set.of(), Set.of());

    /** Normalizes keys and verifies that every required key is allowed. */
    public ProviderConfigurationSpec {
        allowedKeys = normalized(Objects.requireNonNull(allowedKeys, "allowedKeys"), "allowed");
        requiredKeys = normalized(Objects.requireNonNull(requiredKeys, "requiredKeys"), "required");
        if (allowedKeys.size() > ProviderConfiguration.MAX_ENTRIES) {
            throw new IllegalArgumentException("provider configuration spec has too many keys");
        }
        if (!allowedKeys.containsAll(requiredKeys)) {
            throw new IllegalArgumentException("required provider keys must also be allowed");
        }
    }

    /**
     * Returns the shared specification that accepts no configuration keys.
     *
     * @return empty specification
     */
    public static ProviderConfigurationSpec none() {
        return NONE;
    }

    /**
     * Creates an exact configuration-key specification.
     *
     * @param allowedKeys complete set of accepted keys
     * @param requiredKeys subset that must be present
     * @return validated specification
     */
    public static ProviderConfigurationSpec of(Set<String> allowedKeys, Set<String> requiredKeys) {
        return new ProviderConfigurationSpec(allowedKeys, requiredKeys);
    }

    /**
     * Rejects unknown and missing keys before a provider is asked to create an extension.
     *
     * @param configuration provider configuration to validate
     */
    public void validate(ProviderConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        LinkedHashSet<String> unknown = new LinkedHashSet<>(configuration.values().keySet());
        unknown.removeAll(allowedKeys);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown provider configuration key(s): " + unknown);
        }
        LinkedHashSet<String> missing = new LinkedHashSet<>(requiredKeys);
        missing.removeAll(configuration.values().keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("missing provider configuration key(s): " + missing);
        }
    }

    private static Set<String> normalized(Set<String> keys, String label) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String key : keys) {
            String normalized = ProviderConfigurationValues.normalizeKey(Objects.requireNonNull(key));
            if (!result.add(normalized)) {
                throw new IllegalArgumentException(
                        "duplicate normalized " + label + " provider configuration key: " + normalized);
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
