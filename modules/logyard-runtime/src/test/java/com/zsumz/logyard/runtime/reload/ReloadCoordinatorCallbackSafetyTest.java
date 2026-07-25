package com.zsumz.logyard.runtime.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.runtime.assembly.RuntimeAssembly;
import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ReloadCoordinatorCallbackSafetyTest {
    @Test
    void stateInspectionFromAnotherThreadCompletesWhileTheSourceReaderIsRunning() throws Exception {
        Path source = Files.createTempDirectory("logyard-reload-callback-").resolve("logyard.toml");
        Files.writeString(source, config("info"), StandardCharsets.UTF_8);
        ConfigurationSnapshot activeSnapshot = ConfigurationSnapshot.read(source);
        LogyardConfig activeConfig = activeSnapshot.parse(Map.of());
        RuntimeAssembly activeAssembly = LogyardRuntimeFactory.assemble(activeConfig, null);
        activeAssembly.activateCandidateOutputs();
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(activeAssembly.plan());
        LogyardRuntimeFactory.attach(runtime, activeAssembly);
        ExecutorService callback = Executors.newSingleThreadExecutor();
        AtomicReference<ReloadCoordinator> coordinatorReference = new AtomicReference<>();
        ReloadCoordinator coordinator = new ReloadCoordinator(
                source.toString(),
                source,
                () -> {
                    assertCurrentLevel(callback, coordinatorReference.get(), Level.INFO);
                    return ConfigurationSnapshot.read(source);
                },
                runtime,
                activeSnapshot,
                activeAssembly,
                ReloadDiagnostics.silent(),
                Map.of());
        coordinatorReference.set(coordinator);

        try {
            Files.writeString(source, config("debug"), StandardCharsets.UTF_8);
            assertEquals(WatcherReloadOutcome.APPLIED, coordinator.reloadForWatcher());
            assertEquals(Level.DEBUG, coordinator.currentConfig().rootLogger().level());
        } finally {
            callback.shutdownNow();
            runtime.close();
        }
    }

    private static void assertCurrentLevel(ExecutorService callback, ReloadCoordinator coordinator, Level expected) throws IOException {
        try {
            Level observed = callback.submit(() -> coordinator.currentConfig().rootLogger().level()).get(1L, TimeUnit.SECONDS);
            assertEquals(expected, observed);
        } catch (Exception failure) {
            throw new IOException("reload state inspection was blocked by the source reader", failure);
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
