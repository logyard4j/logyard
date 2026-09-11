package com.logyard4j.runtime.installation;

import com.logyard4j.api.LogyardLogger;
import com.logyard4j.api.reload.ReloadResult;
import com.logyard4j.runtime.bootstrap.LogyardBootstrap;
import com.logyard4j.runtime.bootstrap.RuntimeBundle;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeWatcherPolicyReloadTest {
    @Test
    void explicitReloadCannotEnableWatchingWithoutAnInstallationHandoff() throws Exception {
        Path source = Files.createTempDirectory("logyard-reload-enable-watch-").resolve("logyard.toml");
        Files.writeString(source, config("info", false), StandardCharsets.UTF_8);
        try (RuntimeBundle bundle = LogyardBootstrap.start(source)) {
            LogyardLogger logger = bundle.runtime().logger("example.Service");
            Files.writeString(source, config("debug", true), StandardCharsets.UTF_8);

            assertEquals(ReloadResult.REJECTED, bundle.reloadNow());
            assertFalse(logger.isDebugEnabled());
            assertFalse(bundle.watchesConfiguration());
        }
    }

    @Test
    void watchedReloadCannotDisableWatchingWhileClaimingTheCandidateWasApplied() throws Exception {
        Path source = Files.createTempDirectory("logyard-reload-disable-watch-").resolve("logyard.toml");
        Files.writeString(source, config("info", true), StandardCharsets.UTF_8);
        try (RuntimeBundle bundle = LogyardBootstrap.start(source)) {
            LogyardLogger logger = bundle.runtime().logger("example.Service");
            Files.writeString(source, config("debug", false), StandardCharsets.UTF_8);

            assertEquals(ReloadResult.REJECTED, bundle.reloadNow());
            assertFalse(logger.isDebugEnabled());
            assertTrue(bundle.watchesConfiguration());

            Files.writeString(source, config("error", false), StandardCharsets.UTF_8);
            assertEquals(ReloadResult.REJECTED, bundle.reloadNow());
            assertTrue(logger.isInfoEnabled());
            assertTrue(bundle.watchesConfiguration());
        }
    }

    private static String config(String level, boolean watch) {
        return """
                schema = 1
                [runtime]
                watch = %s
                reload_debounce = "10ms"
                shutdown_timeout = "2s"
                internal_status = "off"
                [delivery]
                mode = "sync"
                capacity = 16
                [loggers]
                root = { level = "%s", outputs = ["console"] }
                [outputs.console]
                type = "console"
                stream = "stderr"
                color = { mode = "never" }
                """.formatted(watch, level);
    }
}
