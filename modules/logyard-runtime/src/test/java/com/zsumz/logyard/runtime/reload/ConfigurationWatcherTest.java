package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void continuesWatchingAfterARecoverableReloadCallbackFailure() throws Exception {
        Path directory = Files.createTempDirectory("logyard-hostile-configuration-watcher-");
        Path source = directory.resolve("logyard.toml");
        Files.writeString(source, "schema = 1\n", StandardCharsets.UTF_8);
        CountDownLatch rejected = new CountDownLatch(1);
        CountDownLatch recovered = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        ReloadDiagnostics diagnostics = new ReloadDiagnostics() {
            @Override
            public void rejected(Path rejectedSource, Throwable failure) {
                rejected.countDown();
                throw new AssertionError("hostile diagnostics");
            }
        };

        ConfigurationWatcher watcher = ConfigurationWatcher.start(
                source,
                Duration.ofMillis(25L),
                Duration.ofSeconds(2L),
                () -> {
                    if (attempts.getAndIncrement() == 0) {
                        throw new AssertionError("hostile reload callback");
                    }
                    recovered.countDown();
                },
                diagnostics);
        try {
            Files.writeString(source, "schema = 1\n# first\n", StandardCharsets.UTF_8);
            assertTrue(rejected.await(5L, TimeUnit.SECONDS), "first callback failure was not reported");
            Files.writeString(source, "schema = 1\n# second\n", StandardCharsets.UTF_8);
            assertTrue(recovered.await(5L, TimeUnit.SECONDS), "watcher did not recover after callback failure");
        } finally {
            watcher.close();
        }
    }
}
