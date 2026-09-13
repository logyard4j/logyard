package com.logyard4j.logyard.runtime.reload.coordination;

import static com.logyard4j.logyard.runtime.testing.TomlTestStrings.escapeBasicString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.reload.ReloadResult;
import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.logyard4j.logyard.runtime.assembly.RuntimeAssembly;
import com.logyard4j.logyard.runtime.diagnostics.ReloadDiagnostics;
import com.logyard4j.logyard.runtime.reload.ConfigurationSnapshot;
import com.logyard4j.logyard.runtime.reload.WatcherReloadOutcome;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DuplicateOutputReloadTest {
    @Test
    void watcherMemoizesDuplicatePathsWhileManualReloadUsesTheSameInvalidClassification() throws Exception {
        Path directory = Files.createTempDirectory("logyard-duplicate-reload-");
        Path source = directory.resolve("logyard.toml");
        Path output = directory.resolve("events.jsonl");
        Files.writeString(source, consoleConfig(), StandardCharsets.UTF_8);
        ConfigurationSnapshot activeSnapshot = ConfigurationSnapshot.read(source);
        LogyardConfig activeConfig = activeSnapshot.parse(Map.of());
        RuntimeAssembly activeAssembly = LogyardRuntimeFactory.assemble(activeConfig, null);
        activeAssembly.activateCandidateOutputs();
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(activeAssembly.plan());
        LogyardRuntimeFactory.attach(runtime, activeAssembly);
        AtomicInteger watcherRejections = new AtomicInteger();
        AtomicInteger manualRejections = new AtomicInteger();
        ReloadCoordinator watcher =
                coordinator(source, runtime, activeSnapshot, activeAssembly, watcherRejections);
        ReloadCoordinator manual =
                coordinator(source, runtime, activeSnapshot, activeAssembly, manualRejections);

        try {
            Files.writeString(source, duplicateConfig(output), StandardCharsets.UTF_8);

            assertEquals(WatcherReloadOutcome.INVALID_CANDIDATE, watcher.reloadForWatcher());
            assertEquals(WatcherReloadOutcome.INVALID_CANDIDATE, watcher.reloadForWatcher());
            assertEquals(1, watcherRejections.get());
            assertEquals(ReloadResult.REJECTED, manual.reloadIfChanged());
            assertEquals(1, manualRejections.get());
            assertEquals(Level.INFO, watcher.currentConfig().rootLogger().level());
            assertTrue(Files.notExists(output.resolveSibling(output.getFileName() + ".logyard.lock")));

            Files.writeString(source, repairedConfig(output), StandardCharsets.UTF_8);
            assertEquals(WatcherReloadOutcome.APPLIED, watcher.reloadForWatcher());
            assertEquals(Level.DEBUG, watcher.currentConfig().rootLogger().level());
        } finally {
            runtime.close();
        }
    }

    private static ReloadCoordinator coordinator(
            Path source,
            DefaultLogyardRuntime runtime,
            ConfigurationSnapshot snapshot,
            RuntimeAssembly assembly,
            AtomicInteger rejections) {
        ReloadDiagnostics diagnostics = new ReloadDiagnostics() {
            @Override
            public void rejected(Path ignored, Throwable failure) {
                rejections.incrementAndGet();
            }
        };
        return new ReloadCoordinator(source, runtime, snapshot, assembly, diagnostics, Map.of());
    }

    private static String consoleConfig() {
        return """
                schema = 1
                [runtime]
                internal_status = "off"
                [delivery]
                mode = "sync"
                [loggers]
                root = { level = "info", outputs = ["console"] }
                [outputs.console]
                type = "console"
                color = { mode = "never" }
                """;
    }

    private static String duplicateConfig(Path output) {
        Path equivalent = output.getParent().resolve("nested/../" + output.getFileName());
        return """
                schema = 1
                [runtime]
                internal_status = "off"
                [delivery]
                mode = "sync"
                [loggers]
                root = { level = "debug", outputs = ["audit", "events"] }
                [outputs.audit]
                type = "file"
                path = "%s"
                [outputs.events]
                type = "file"
                path = "%s"
                """.formatted(escapeBasicString(output), escapeBasicString(equivalent));
    }

    private static String repairedConfig(Path output) {
        return """
                schema = 1
                [runtime]
                internal_status = "off"
                [delivery]
                mode = "sync"
                [loggers]
                root = { level = "debug", outputs = ["events"] }
                [outputs.events]
                type = "file"
                path = "%s"
                """.formatted(escapeBasicString(output));
    }
}
