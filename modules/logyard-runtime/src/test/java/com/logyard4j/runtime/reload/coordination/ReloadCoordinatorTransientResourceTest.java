package com.logyard4j.runtime.reload.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.logyard4j.api.Level;
import com.logyard4j.config.LogyardConfig;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.output.json.encoding.ResourceAttributes;
import com.logyard4j.output.json.file.JsonFileSink;
import com.logyard4j.runtime.assembly.LogyardRuntimeFactory;
import com.logyard4j.runtime.assembly.RuntimeAssembly;
import com.logyard4j.runtime.diagnostics.ReloadDiagnostics;
import com.logyard4j.runtime.reload.ConfigurationSnapshot;
import com.logyard4j.runtime.reload.WatcherReloadOutcome;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ReloadCoordinatorTransientResourceTest {
    @Test
    void retriesTheSameValidDigestAfterItsFileLeaseBecomesAvailable() throws Exception {
        Path directory = Files.createTempDirectory("logyard-reload-lease-");
        Path source = directory.resolve("logyard.toml");
        Path output = directory.resolve("events.jsonl");
        Files.writeString(source, consoleConfig(), StandardCharsets.UTF_8);
        ConfigurationSnapshot activeSnapshot = ConfigurationSnapshot.read(source);
        LogyardConfig activeConfig = activeSnapshot.parse(Map.of());
        RuntimeAssembly activeAssembly = LogyardRuntimeFactory.assemble(activeConfig, null);
        activeAssembly.activateCandidateOutputs();
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(activeAssembly.plan());
        LogyardRuntimeFactory.attach(runtime, activeAssembly);
        ReloadCoordinator coordinator = new ReloadCoordinator(
                source,
                runtime,
                activeSnapshot,
                activeAssembly,
                ReloadDiagnostics.silent(),
                Map.of());

        try {
            try (JsonFileSink blocker = new JsonFileSink(
                    output,
                    ResourceAttributes.service("blocker", "test", "1"),
                    1_024,
                    Duration.ZERO,
                    true)) {
                assertEquals(output.toAbsolutePath().normalize(), blocker.path());
                Files.writeString(source, fileConfig(output), StandardCharsets.UTF_8);
                assertEquals(WatcherReloadOutcome.TRANSIENT_RETRY, coordinator.reloadForWatcher());
                assertEquals(Level.INFO, coordinator.currentConfig().rootLogger().level());
            }

            assertEquals(WatcherReloadOutcome.APPLIED, coordinator.reloadForWatcher());
            assertEquals(Level.DEBUG, coordinator.currentConfig().rootLogger().level());
        } finally {
            runtime.close();
        }
    }

    private static String consoleConfig() {
        return """
                schema = 1
                [runtime]
                internal_status = "off"
                [delivery]
                mode = "sync"
                capacity = 16
                [loggers]
                root = { level = "info", outputs = ["console"] }
                [outputs.console]
                type = "console"
                stream = "stderr"
                color = { mode = "never" }
                """;
    }

    private static String fileConfig(Path output) {
        return """
                schema = 1
                [runtime]
                internal_status = "off"
                [delivery]
                mode = "sync"
                capacity = 16
                [loggers]
                root = { level = "debug", outputs = ["json"] }
                [outputs.json]
                type = "file"
                path = "%s"
                append = true
                buffer = "4KiB"
                flush = "0s"
                """.formatted(output);
    }
}
