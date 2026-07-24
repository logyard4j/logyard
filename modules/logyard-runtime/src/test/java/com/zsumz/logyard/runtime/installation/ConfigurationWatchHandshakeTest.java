package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
        return """
                schema = 1
                [runtime]
                watch = %s
                reload_debounce = "25ms"
                shutdown_timeout = "2s"
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
                """.formatted(watch, level);
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
