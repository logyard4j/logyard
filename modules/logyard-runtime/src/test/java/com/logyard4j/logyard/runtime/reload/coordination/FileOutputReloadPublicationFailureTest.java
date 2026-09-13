package com.logyard4j.logyard.runtime.reload.coordination;

import static com.logyard4j.logyard.runtime.testing.TomlTestStrings.escapeBasicString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.logyard4j.logyard.api.LogyardLogger;
import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.logyard.core.runtime.RuntimeReloadDeferredException;
import com.logyard4j.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.logyard4j.logyard.runtime.assembly.RuntimeAssembly;
import com.logyard4j.logyard.runtime.diagnostics.ReloadDiagnostics;
import com.logyard4j.logyard.runtime.reload.ConfigurationSnapshot;
import com.logyard4j.logyard.runtime.reload.ConfigurationInputs;
import com.logyard4j.logyard.runtime.reload.WatcherReloadOutcome;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class FileOutputReloadPublicationFailureTest {
    @Test
    void failedPublicationPreservesAnActivatedUnusedFileUntilACommittedFirstWrite() throws Exception {
        Path directory = Files.createTempDirectory("logyard-reload-publication-");
        Path source = directory.resolve("logyard.toml");
        Path output = directory.resolve("events.jsonl");
        Files.writeString(source, consoleConfig(), StandardCharsets.UTF_8);
        Files.writeString(output, "KEEP-ME\n", StandardCharsets.UTF_8);
        ConfigurationSnapshot activeSnapshot = ConfigurationSnapshot.read(source);
        LogyardConfig activeConfig = activeSnapshot.parse(Map.of());
        RuntimeAssembly activeAssembly = LogyardRuntimeFactory.assemble(activeConfig, null);
        activeAssembly.activateCandidateOutputs();
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(activeAssembly.plan());
        LogyardRuntimeFactory.attach(runtime, activeAssembly);
        AtomicInteger publications = new AtomicInteger();
        ReloadCoordinator coordinator = new ReloadCoordinator(
                source.toString(),
                source,
                () -> ConfigurationSnapshot.read(source),
                runtime,
                plan -> {
                    if (publications.getAndIncrement() == 0) {
                        throw new RuntimeReloadDeferredException("publication capacity unavailable");
                    }
                    runtime.reload(plan);
                },
                activeSnapshot,
                activeAssembly,
                ReloadDiagnostics.silent(),
                ConfigurationInputs.capture(Map.of()));

        try {
            Files.writeString(source, fileConfig(output), StandardCharsets.UTF_8);
            assertEquals(WatcherReloadOutcome.BUSY_RETRY, coordinator.reloadForWatcher());
            assertEquals("KEEP-ME\n", Files.readString(output, StandardCharsets.UTF_8));

            assertEquals(WatcherReloadOutcome.APPLIED, coordinator.reloadForWatcher());
            assertEquals("KEEP-ME\n", Files.readString(output, StandardCharsets.UTF_8));

            LogyardLogger logger = runtime.logger("test.Logger");
            logger.info("committed event");
            runtime.flush();
            assertFalse(Files.readString(output, StandardCharsets.UTF_8).contains("KEEP-ME"));
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
                [loggers]
                root = { level = "info", outputs = ["console"] }
                [outputs.console]
                type = "console"
                color = { mode = "never" }
                """;
    }

    private static String fileConfig(Path output) {
        return """
                schema = 1
                [runtime]
                internal_status = "off"
                [delivery]
                mode = "async"
                capacity = 16
                [loggers]
                root = { level = "info", outputs = ["json"] }
                [outputs.json]
                type = "file"
                path = "%s"
                append = false
                flush = "0s"
                """.formatted(escapeBasicString(output));
    }
}
