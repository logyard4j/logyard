package com.logyard4j.config.delivery;

import com.logyard4j.api.Level;
import com.logyard4j.api.delivery.OverflowAction;
import com.logyard4j.config.loading.LogyardConfigLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WaitDropConfigurationTest {
    @Test
    void preservesLatencyFirstDefaultsAndAddsAnErrorAdmissionWindow() {
        var config = LogyardConfigLoader.parse("""
                schema = 1
                [delivery.overflow]
                error = { action = "wait_drop", timeout = "20ms" }
                [outputs.console]
                type = "console"
                """, "wait-drop.toml", Path.of("."), Map.of());
        assertEquals(OverflowAction.DROP, config.delivery().overflow().get(Level.INFO).action());
        assertEquals(OverflowAction.WAIT_DROP, config.delivery().overflow().get(Level.ERROR).action());
        assertEquals(Duration.ofMillis(20), config.delivery().overflow().get(Level.ERROR).after());
    }
}
