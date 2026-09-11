package com.logyard4j.config.loading.overlay;

import com.logyard4j.api.delivery.OverflowAction;
import com.logyard4j.api.Level;
import com.logyard4j.config.ConfigurationException;
import com.logyard4j.config.loading.LogyardConfigLoader;
import com.logyard4j.config.schema.ConfigSchema;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayIntegrationTest {
    @Test
    void profilesPreserveResourceExclusionsAndBoundedWaitDrop() {
        var config = LogyardConfigLoader.parseDetailed("""
                schema = 1
                [outputs.console]
                type = "console"
                [resource]
                exclude = ["service.version"]
                [profiles.prod.delivery.overflow]
                error = { action = "wait_drop", timeout = "20ms" }
                """, "profile.toml", Path.of("."), Map.of(), new ConfigOverlays("prod", java.util.List.of())).config();
        assertEquals(OverflowAction.WAIT_DROP, config.delivery().overflow().get(Level.ERROR).action());
        assertEquals(java.util.List.of("service.version"), config.resource().exclude());
        assertTrue(ConfigSchema.keysFor("resource").containsAll(java.util.Set.of("include", "exclude")));
    }

    @Test
    void boundsOverrideInputBeforeSplittingOrParsingIt() {
        assertThrows(ConfigurationException.class, () -> ConfigOverlays.fromProcess(
                Map.of("LOGYARD_OVERRIDES", " ".repeat(400_000)), new Properties()));
        assertThrows(ConfigurationException.class, () -> ConfigOverlays.fromProcess(
                Map.of("LOGYARD_OVERRIDES", "delivery.capacity=16;".repeat(65)), new Properties()));
        var properties = new Properties();
        for (int index = 0; index < 65; index++) properties.setProperty("logyard.override.key" + index, "value");
        assertThrows(ConfigurationException.class, () -> ConfigOverlays.fromProcess(Map.of(), properties));
    }
}
