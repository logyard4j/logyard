package com.zsumz.logyard.runtime.reload.coordination;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.runtime.assembly.RuntimeAssembly;
import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;
import com.zsumz.logyard.runtime.reload.WatcherReloadOutcome;
import com.zsumz.logyard.runtime.reload.watcher.ConfigurationWatcher;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RejectedConfigurationReconciliationTest {
    @Test
    void reconciliationReadsButNeverReparsesOrReportsTheSameRejectedDigest() throws Exception {
        Path source = Files.createTempDirectory("logyard-rejected-digest-").resolve("logyard.toml");
        String activeText = config("info");
        Files.writeString(source, activeText, StandardCharsets.UTF_8);
        ConfigurationSnapshot activeSnapshot = ConfigurationSnapshot.read(source);
        LogyardConfig activeConfig = activeSnapshot.parse(Map.of());
        RuntimeAssembly activeAssembly = LogyardRuntimeFactory.assemble(activeConfig, null);
        activeAssembly.activateCandidateOutputs();
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(activeAssembly.plan());
        LogyardRuntimeFactory.attach(runtime, activeAssembly);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        ReloadCoordinator coordinator = new ReloadCoordinator(
                source.toString(),
                source,
                () -> {
                    reads.incrementAndGet();
                    return ConfigurationSnapshot.read(source);
                },
                runtime,
                activeSnapshot,
                activeAssembly,
                new ReloadDiagnostics() {
                    @Override
                    public void rejected(Path rejectedSource, Throwable failure) {
                        rejections.incrementAndGet();
                    }
                },
                Map.of());
        try {
            try (ConfigurationWatcher watcher = ConfigurationWatcher.start(
                    source,
                    Duration.ofMillis(25L),
                    Duration.ofSeconds(2L),
                    coordinator,
                    ReloadDiagnostics.silent())) {
                assertTrue(watcher.isRunning(), "reconciliation watcher did not start");
                Files.writeString(source, activeText + "\ninvalid = true\n", StandardCharsets.UTF_8);

                await(() -> rejections.get() == 1, "invalid content was not rejected");
                await(() -> reads.get() >= 3, "source was not checked through two reconciliation intervals");
            }

            assertTrue(reads.get() >= 3, "reconciliation stopped checking the source");
            assertEquals(1, rejections.get(), "identical invalid content was parsed or reported repeatedly");

            Files.writeString(source, activeText + "\nanother_invalid = true\n", StandardCharsets.UTF_8);
            assertEquals(WatcherReloadOutcome.INVALID_CANDIDATE, coordinator.reloadForWatcher());
            assertEquals(2, rejections.get(), "new invalid content was not attempted exactly once");

            Files.writeString(source, activeText, StandardCharsets.UTF_8);
            assertEquals(WatcherReloadOutcome.UNCHANGED, coordinator.reloadForWatcher());
            assertEquals(Level.INFO, coordinator.currentConfig().rootLogger().level());

            Files.writeString(source, activeText + "\nanother_invalid = true\n", StandardCharsets.UTF_8);
            assertEquals(WatcherReloadOutcome.INVALID_CANDIDATE, coordinator.reloadForWatcher());
            assertEquals(3, rejections.get(), "returning to active content did not clear rejected-digest state");
        } finally {
            runtime.close();
        }
    }

    private static void await(BooleanSupplier condition, String failure) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5L).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(failure);
            }
            Thread.sleep(10L);
        }
    }

    private static String config(String level) {
        return """
                schema = 1
                [runtime]
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
                """.formatted(level);
    }
}
