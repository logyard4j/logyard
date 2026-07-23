package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigurationWatcherTest {
    @Test
    void reloadsAfterTheConfigurationFileChanges() throws Exception {
        Path directory = Files.createTempDirectory("logyard-configuration-watcher-");
        Path source = directory.resolve("logyard.toml");
        Files.writeString(source, "schema = 1\n", StandardCharsets.UTF_8);
        CountDownLatch reloaded = new CountDownLatch(1);
        AtomicReference<Throwable> watcherFailure = new AtomicReference<>();
        ReloadDiagnostics diagnostics = new ReloadDiagnostics() {
            @Override
            public void watcherStopped(Path stoppedSource, Throwable failure) {
                watcherFailure.set(failure);
            }
        };

        ConfigurationWatcher watcher = ConfigurationWatcher.start(
                source,
                Duration.ofMillis(25L),
                Duration.ofSeconds(2L),
                reloaded::countDown,
                diagnostics);
        try {
            Files.writeString(source, "schema = 1\n# changed\n", StandardCharsets.UTF_8);
            assertTrue(reloaded.await(5L, TimeUnit.SECONDS), () -> "watcher did not reload; failure=" + watcherFailure.get());
        } finally {
            watcher.close();
        }
    }
}
