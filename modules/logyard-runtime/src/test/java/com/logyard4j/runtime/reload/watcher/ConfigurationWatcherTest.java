package com.logyard4j.runtime.reload.watcher;

import com.logyard4j.runtime.diagnostics.ReloadDiagnostics;
import com.logyard4j.runtime.reload.WatcherReloadOutcome;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                () -> {
                    reloaded.countDown();
                    return WatcherReloadOutcome.APPLIED;
                },
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
                    return WatcherReloadOutcome.APPLIED;
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

    @Test
    void rejectedReloadRetainsTheDirtyFileUntilTheLatestContentIsApplied() throws Exception {
        Path directory = Files.createTempDirectory("logyard-durable-configuration-watcher-");
        Path source = directory.resolve("logyard.toml");
        Files.writeString(source, "version = 1\n", StandardCharsets.UTF_8);
        CountDownLatch rejectedAttemptEntered = new CountDownLatch(1);
        CountDownLatch allowRejectedAttempt = new CountDownLatch(1);
        CountDownLatch latestApplied = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> active = new AtomicReference<>("version = 1\n");

        ConfigurationWatcher watcher = ConfigurationWatcher.start(
                source,
                Duration.ofMillis(25L),
                Duration.ofSeconds(2L),
                () -> {
                    String observed = read(source);
                    if (attempts.getAndIncrement() == 0) {
                        rejectedAttemptEntered.countDown();
                        await(allowRejectedAttempt);
                        active.set(observed);
                        return WatcherReloadOutcome.BUSY_RETRY;
                    }
                    active.set(observed);
                    latestApplied.countDown();
                    return WatcherReloadOutcome.APPLIED;
                },
                ReloadDiagnostics.silent());
        try {
            Files.writeString(source, "version = 2\n", StandardCharsets.UTF_8);
            assertTrue(rejectedAttemptEntered.await(5L, TimeUnit.SECONDS), "rejected reload did not start");
            Files.writeString(source, "version = 3\n", StandardCharsets.UTF_8);
            allowRejectedAttempt.countDown();

            assertTrue(latestApplied.await(5L, TimeUnit.SECONDS), "dirty configuration was not retried");
            assertTrue(active.get().contains("version = 3"), () -> "active configuration was " + active.get());
        } finally {
            allowRejectedAttempt.countDown();
            watcher.close();
        }
    }

    @Test
    void invalidCandidateIsNotRetriedBeforeReconciliationOrAnotherFileEvent() throws Exception {
        Path directory = Files.createTempDirectory("logyard-invalid-configuration-watcher-");
        Path source = directory.resolve("logyard.toml");
        Files.writeString(source, "schema = 1\n", StandardCharsets.UTF_8);
        CountDownLatch firstAttempt = new CountDownLatch(1);
        CountDownLatch unexpectedRetry = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();

        ConfigurationWatcher watcher = ConfigurationWatcher.start(
                source,
                Duration.ofMillis(25L),
                Duration.ofSeconds(2L),
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        firstAttempt.countDown();
                    } else {
                        unexpectedRetry.countDown();
                    }
                    return WatcherReloadOutcome.INVALID_CANDIDATE;
                },
                ReloadDiagnostics.silent());
        try {
            Files.writeString(source, "invalid = true\n", StandardCharsets.UTF_8);
            assertTrue(firstAttempt.await(5L, TimeUnit.SECONDS), "invalid configuration was not observed");
            assertFalse(unexpectedRetry.await(300L, TimeUnit.MILLISECONDS), "invalid configuration retried before reconciliation");
            assertEquals(1, attempts.get());
        } finally {
            watcher.close();
        }
    }

    @Test
    void zeroShutdownTimeoutInitiatesStopWithoutReportingADeadWatcherAsRunning() throws Exception {
        Path directory = Files.createTempDirectory("logyard-zero-timeout-watcher-");
        Path source = directory.resolve("logyard.toml");
        Files.writeString(source, "schema = 1\n", StandardCharsets.UTF_8);
        ConfigurationWatcher watcher = ConfigurationWatcher.start(
                source,
                Duration.ofMillis(25L),
                Duration.ZERO,
                () -> WatcherReloadOutcome.APPLIED,
                ReloadDiagnostics.silent());

        assertTrue(watcher.isRunning());
        watcher.close();

        assertFalse(watcher.isRunning());
    }

    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("failed to read test configuration", failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test barrier interrupted", interrupted);
        }
    }
}
