package com.logyard4j.runtime.reload.watcher;

import com.logyard4j.runtime.diagnostics.ReloadDiagnostics;
import com.logyard4j.runtime.reload.WatcherReloadOutcome;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigurationWatcherPortabilityTest {
    @Test
    void reloadsAfterAKubernetesProjectedVolumeDataLinkSwap() throws Exception {
        Path volume = Files.createTempDirectory("logyard-projected-volume-");
        Path first = Files.createDirectory(volume.resolve("..2026_01"));
        Path second = Files.createDirectory(volume.resolve("..2026_02"));
        Files.writeString(first.resolve("logyard.toml"), "version=one\n", StandardCharsets.UTF_8);
        Files.writeString(second.resolve("logyard.toml"), "version=two\n", StandardCharsets.UTF_8);
        Files.createSymbolicLink(volume.resolve("..data"), first.getFileName());
        Path source = Files.createSymbolicLink(volume.resolve("logyard.toml"), Path.of("..data", "logyard.toml"));
        CountDownLatch reloaded = new CountDownLatch(1);
        AtomicReference<String> observed = new AtomicReference<>("version=one\n");

        try (ConfigurationWatcher watcher = watcher(source, () -> {
            String content = read(source);
            observed.set(content);
            if (content.contains("two")) {
                reloaded.countDown();
            }
            return WatcherReloadOutcome.APPLIED;
        }, new AtomicReference<>())) {
            assertTrue(watcher.isRunning());
            Path replacement = Files.createSymbolicLink(volume.resolve("..data-next"), second.getFileName());
            replace(replacement, volume.resolve("..data"));

            assertTrue(reloaded.await(5L, TimeUnit.SECONDS), () -> "projected content remained " + observed.get());
        }
    }

    @Test
    void invalidatedDirectoryWatchReregistersAfterTheDirectoryReturns() throws Exception {
        Path root = Files.createTempDirectory("logyard-watch-reregistration-");
        Path directory = Files.createDirectory(root.resolve("configuration"));
        Path source = directory.resolve("logyard.toml");
        Files.writeString(source, "version=one\n", StandardCharsets.UTF_8);
        CountDownLatch reloaded = new CountDownLatch(1);
        AtomicReference<Throwable> stopped = new AtomicReference<>();

        try (ConfigurationWatcher watcher = watcher(source, () -> {
            if (read(source).contains("two")) {
                reloaded.countDown();
            }
            return WatcherReloadOutcome.APPLIED;
        }, stopped)) {
            assertTrue(watcher.isRunning());
            Files.delete(source);
            Files.delete(directory);
            Thread.sleep(200L);
            Files.createDirectory(directory);
            Files.writeString(source, "version=two\n", StandardCharsets.UTF_8);

            assertTrue(reloaded.await(5L, TimeUnit.SECONDS), "watch did not recover after its directory returned");
            assertNull(stopped.get(), "watcher stopped instead of re-registering: " + stopped.get());
        }
    }

    private static ConfigurationWatcher watcher(
            Path source,
            java.util.function.Supplier<WatcherReloadOutcome> reload,
            AtomicReference<Throwable> stopped) {
        return ConfigurationWatcher.start(
                source,
                Duration.ofMillis(25L),
                Duration.ofSeconds(2L),
                reload,
                new ReloadDiagnostics() {
                    @Override
                    public void watcherStopped(Path stoppedSource, Throwable failure) {
                        stopped.set(failure);
                    }
                });
    }

    private static void replace(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("failed to read watched source", failure);
        }
    }
}
