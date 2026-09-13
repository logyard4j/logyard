package com.logyard4j.logyard.runtime.reload;

import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.config.loading.overlay.ConfigOverlays;
import java.util.Map;
import java.util.Objects;

/** Immutable launch inputs shared by installation, source handoff, and every file reload. */
public record ConfigurationInputs(Map<String, String> environment, ConfigOverlays overlays) {
    public ConfigurationInputs {
        environment = Map.copyOf(environment);
        Objects.requireNonNull(overlays, "overlays");
    }

    public static ConfigurationInputs capture(Map<String, String> environment) {
        Map<String, String> captured = Map.copyOf(environment);
        return new ConfigurationInputs(captured, ConfigOverlays.fromProcess(captured, System.getProperties()));
    }

    public LogyardConfig parse(ConfigurationSnapshot snapshot) {
        return snapshot.parse(environment, overlays);
    }
}
