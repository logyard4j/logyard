package com.zsumz.logyard.config.delivery;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.delivery.OverflowAction;
import com.zsumz.logyard.config.loading.LogyardConfigLoader;
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
