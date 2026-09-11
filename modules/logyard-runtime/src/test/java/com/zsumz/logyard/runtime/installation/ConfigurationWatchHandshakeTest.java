package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.runtime.diagnostics.StderrReloadDiagnostics;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;
import com.zsumz.logyard.runtime.reload.ConfigurationInputs;
import com.zsumz.logyard.runtime.reload.WatcherReloadOutcome;
import com.zsumz.logyard.runtime.installation.process.RuntimeInstallationLease;
import com.zsumz.logyard.runtime.installation.process.RuntimeInstallationManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class ConfigurationWatchHandshakeTest {
    @Test
    void initialInstallationCommitsASnapshotReadAfterWatcherRegistration() throws Exception {
        Harness harness = Harness.create();
        Path source = Files.createTempDirectory("logyard-start-handshake-").resolve("logyard.toml");
        Files.writeString(source, config("info", true), StandardCharsets.UTF_8);
        BlockingSnapshotSource snapshots = new BlockingSnapshotSource(source);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RuntimeInstallationLease> acquisition =
                    executor.submit(() -> harness.manager().acquireApplication(snapshots.request()));
            assertTrue(snapshots.initialSnapshotCaptured.await(1L, TimeUnit.SECONDS));
            Files.writeString(source, config("debug", true), StandardCharsets.UTF_8);
            snapshots.allowInitialSnapshot.countDown();

            try (RuntimeInstallationLease lease = acquisition.get(5L, TimeUnit.SECONDS)) {
                assertTrue(lease.runtime().logger("example.Service").isDebugEnabled());
                assertTrue(snapshots.reads.get() >= 2, "configuration was not reread after watcher registration");
            }
        } finally {
            snapshots.allowInitialSnapshot.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void frameworkSourceHandoffCommitsASnapshotReadAfterNewWatcherRegistration() throws Exception {
        Harness harness = Harness.create();
        RuntimeInstallationLease application =
                harness.manager().acquireApplication(textRequest("initial", config("info", false)));
        LogyardLogger existing = application.runtime().logger("example.Service");
        Path source = Files.createTempDirectory("logyard-handoff-handshake-").resolve("logyard.toml");
        Files.writeString(source, config("error", true), StandardCharsets.UTF_8);
        BlockingSnapshotSource snapshots = new BlockingSnapshotSource(source);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RuntimeInstallationLease> handoff =
                    executor.submit(() -> harness.manager().acquireFramework(snapshots.request()));
            assertTrue(snapshots.initialSnapshotCaptured.await(1L, TimeUnit.SECONDS));
            Files.writeString(source, config("debug", true), StandardCharsets.UTF_8);
            snapshots.allowInitialSnapshot.countDown();

            try (RuntimeInstallationLease framework = handoff.get(5L, TimeUnit.SECONDS)) {
                assertTrue(framework.active());
                assertTrue(existing.isDebugEnabled());
                assertTrue(snapshots.reads.get() >= 2, "handoff source was not reread after watcher registration");
            }
        } finally {
            snapshots.allowInitialSnapshot.countDown();
            application.close();
            executor.shutdownNow();
        }
    }

    @Test
    void watcherUsesAllPolicyValuesFromThePostRegistrationSnapshot() throws Exception {
        Path source = Files.createTempDirectory("logyard-policy-handshake-").resolve("logyard.toml");
        Files.writeString(source, config("info", true, "5s", "0s", "off"), StandardCharsets.UTF_8);
        ConfigurationSnapshot initialSnapshot = ConfigurationSnapshot.read(source);
        Files.writeString(source, config("info", true, "10ms", "2s", "warn"), StandardCharsets.UTF_8);
        ConfigurationInstallationRequest request =
                new ConfigurationInstallationRequest(source.toString(), source, source, () -> ConfigurationSnapshot.read(source));
        PreparedRuntimeConfiguration prepared = PreparedRuntimeConfiguration.prepare(
                request,
                initialSnapshot,
                null,
                ConfigurationInputs.capture(Map.of()),
                () -> WatcherReloadOutcome.INVALID_CANDIDATE);
        try {
            ConfigurationWatcherPolicy policy = prepared.watcherPolicy();
            assertEquals(Duration.ofMillis(10L), policy.debounce());
            assertEquals(Duration.ofSeconds(2L), policy.closeTimeout());
            assertInstanceOf(StderrReloadDiagnostics.class, policy.diagnostics());
        } finally {
            prepared.closeWatcher(new IllegalStateException("test cleanup"));
        }
    }

    @Test
    void postRegistrationSnapshotCanDisableTheProvisionalWatcher() throws Exception {
        Harness harness = Harness.create();
        Path source = Files.createTempDirectory("logyard-disable-handshake-").resolve("logyard.toml");
        Files.writeString(source, config("info", true), StandardCharsets.UTF_8);
        BlockingSnapshotSource snapshots = new BlockingSnapshotSource(source);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RuntimeInstallationLease> acquisition =
                    executor.submit(() -> harness.manager().acquireApplication(snapshots.request()));
            assertTrue(snapshots.initialSnapshotCaptured.await(1L, TimeUnit.SECONDS));
            Files.writeString(source, config("debug", false), StandardCharsets.UTF_8);
            snapshots.allowInitialSnapshot.countDown();

            try (RuntimeInstallationLease lease = acquisition.get(5L, TimeUnit.SECONDS)) {
                assertTrue(lease.runtime().logger("example.Service").isDebugEnabled());
                assertFalse(lease.watchesConfiguration());
            }
        } finally {
            snapshots.allowInitialSnapshot.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void postRegistrationSnapshotCanEnableAWatcherInitiallyDisabledByTheSource() throws Exception {
        Harness harness = Harness.create();
        Path source = Files.createTempDirectory("logyard-enable-handshake-").resolve("logyard.toml");
        Files.writeString(source, config("info", false), StandardCharsets.UTF_8);
        BlockingSnapshotSource snapshots = new BlockingSnapshotSource(source);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RuntimeInstallationLease> acquisition =
                    executor.submit(() -> harness.manager().acquireApplication(snapshots.request()));
            assertTrue(snapshots.initialSnapshotCaptured.await(1L, TimeUnit.SECONDS));
            Files.writeString(source, config("debug", true), StandardCharsets.UTF_8);
            snapshots.allowInitialSnapshot.countDown();

            try (RuntimeInstallationLease lease = acquisition.get(5L, TimeUnit.SECONDS)) {
                assertTrue(lease.runtime().logger("example.Service").isDebugEnabled());
                assertTrue(lease.watchesConfiguration());
                assertTrue(snapshots.reads.get() >= 2, "disabled source was not reread after provisional registration");
            }
        } finally {
            snapshots.allowInitialSnapshot.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void frameworkHandoffCanEnableAWatcherInitiallyDisabledByTheNewSource() throws Exception {
        Harness harness = Harness.create();
        RuntimeInstallationLease application =
                harness.manager().acquireApplication(textRequest("initial", config("info", false)));
        LogyardLogger existing = application.runtime().logger("example.Service");
        Path source = Files.createTempDirectory("logyard-enable-handoff-").resolve("logyard.toml");
        Files.writeString(source, config("error", false), StandardCharsets.UTF_8);
        BlockingSnapshotSource snapshots = new BlockingSnapshotSource(source);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RuntimeInstallationLease> handoff =
                    executor.submit(() -> harness.manager().acquireFramework(snapshots.request()));
            assertTrue(snapshots.initialSnapshotCaptured.await(1L, TimeUnit.SECONDS));
            Files.writeString(source, config("debug", true), StandardCharsets.UTF_8);
            snapshots.allowInitialSnapshot.countDown();

            try (RuntimeInstallationLease framework = handoff.get(5L, TimeUnit.SECONDS)) {
                assertTrue(framework.active());
                assertTrue(framework.watchesConfiguration());
                assertTrue(existing.isDebugEnabled());
                assertTrue(snapshots.reads.get() >= 2, "disabled handoff source was not reread after registration");
            }
        } finally {
            snapshots.allowInitialSnapshot.countDown();
            application.close();
            executor.shutdownNow();
        }
    }

    private static ConfigurationInstallationRequest textRequest(String description, String text) {
        return new ConfigurationInstallationRequest(
                description,
                null,
                new Object(),
                () -> ConfigurationSnapshot.capture(
                        description,
                        Path.of(".").toAbsolutePath(),
                        null,
                        text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String config(String level, boolean watch) {
        return config(level, watch, "25ms", "2s", "off");
    }

    private static String config(
            String level,
            boolean watch,
            String debounce,
            String shutdownTimeout,
            String internalStatus) {
        return """
                schema = 1
                [runtime]
                watch = %s
                reload_debounce = "%s"
                shutdown_timeout = "%s"
                internal_status = "%s"
                [delivery]
                mode = "sync"
                capacity = 16
                [loggers]
                root = { level = "%s", outputs = ["console"] }
                [outputs.console]
                type = "console"
                stream = "stderr"
                color = { mode = "never" }
                """.formatted(watch, debounce, shutdownTimeout, internalStatus, level);
    }

    private static final class BlockingSnapshotSource {
        private final Path source;
        private final AtomicInteger reads = new AtomicInteger();
        private final CountDownLatch initialSnapshotCaptured = new CountDownLatch(1);
        private final CountDownLatch allowInitialSnapshot = new CountDownLatch(1);

        private BlockingSnapshotSource(Path source) {
            this.source = source.toAbsolutePath().normalize();
        }

        ConfigurationInstallationRequest request() {
            return new ConfigurationInstallationRequest(source.toString(), source, source, this::read);
        }

        private ConfigurationSnapshot read() throws IOException {
            ConfigurationSnapshot snapshot = ConfigurationSnapshot.read(source);
            if (reads.incrementAndGet() == 1) {
                initialSnapshotCaptured.countDown();
                await(allowInitialSnapshot);
            }
            return snapshot;
        }

        private static void await(CountDownLatch latch) throws IOException {
            try {
                latch.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("snapshot barrier interrupted", interrupted);
            }
        }
    }

    private record Harness(RuntimeInstallationManager manager) {
        static Harness create() {
            TestGlobal global = new TestGlobal();
            return new Harness(new RuntimeInstallationManager(global, ignored -> true, Map::of));
        }
    }

    private static final class TestGlobal implements GlobalRuntimeAccess {
        private final AtomicReference<LogyardRuntime> current = new AtomicReference<>();

        @Override public LogyardRuntime current() { return current.get(); }

        @Override
        public void install(LogyardRuntime runtime) {
            if (!current.compareAndSet(null, runtime)) {
                throw new IllegalStateException("runtime already installed");
            }
        }

        @Override
        public boolean shutdownIfCurrent(LogyardRuntime runtime) {
            if (!current.compareAndSet(runtime, null)) {
                return false;
            }
            runtime.close();
            return true;
        }
    }
}
