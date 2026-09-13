package com.logyard4j.logyard.config.loading.result;

import com.logyard4j.logyard.config.LogyardConfig;

import java.util.List;
import java.util.Objects;

/**
 * The result of loading one configuration source with overlays resolved.
 *
 * <p>The configuration is the selected variant: the base document, the active
 * profile's tables merged over it when one is selected, and every key-level override
 * applied. All declared profiles were validated with the same overrides even when not
 * selected. {@code activeProfile} is {@code null} when the base configuration runs,
 * and {@code locations} answers where each effective value came from.</p>
 */
public record LoadedConfiguration(
        LogyardConfig config,
        String activeProfile,
        List<String> availableProfiles,
        ConfigLocations locations) {
    public LoadedConfiguration {
        Objects.requireNonNull(config, "config");
        availableProfiles = List.copyOf(Objects.requireNonNull(availableProfiles, "availableProfiles"));
        Objects.requireNonNull(locations, "locations");
    }
}
