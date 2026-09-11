package com.logyard4j.config.runtime;

import com.logyard4j.config.ConfigurationException;
import com.logyard4j.config.LogyardConfig;
import com.logyard4j.config.loading.LogyardConfigLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeReloadConfigTest {
    @Test
    void parsesWatchAndDebounce() {
        RuntimeConfig runtime = parse("watch = true\nreload_debounce = \"125ms\"").runtime();
        assertTrue(runtime.watch());
        org.junit.jupiter.api.Assertions.assertEquals(Duration.ofMillis(125), runtime.reloadDebounce());
    }

    @Test
    void defaultsWatchOff() {
        assertFalse(parse("").runtime().watch());
    }

    @Test
    void rejectsExcessiveDebounce() {
        assertThrows(ConfigurationException.class, () -> parse("reload_debounce = \"31s\""));
    }

    private static LogyardConfig parse(String runtime) {
        String text = """
                schema = 1
                [service]
                name = "test"
                [runtime]
                %s
                [delivery]
                mode = "sync"
                capacity = 16
                [context]
                [loggers]
                root = { level = "info", outputs = ["console"] }
                [outputs.console]
                type = "console"
                color = { mode = "never" }
                """.formatted(runtime);
        return LogyardConfigLoader.parse(text, "test.toml", Path.of("."), Map.of());
    }
}
