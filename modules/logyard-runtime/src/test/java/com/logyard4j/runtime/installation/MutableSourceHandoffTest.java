package com.logyard4j.runtime.installation;

import com.logyard4j.api.LogyardLogger;
import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.api.reload.ReloadResult;
import com.logyard4j.runtime.reload.ConfigurationSnapshot;
import com.logyard4j.runtime.reload.watcher.ConfigurationWatcher;
import com.logyard4j.runtime.installation.process.RuntimeInstallationLease;
import com.logyard4j.runtime.installation.process.RuntimeInstallationManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MutableSourceHandoffTest {
    @Test
    void sameSourceHandoffCommitsAFileChangeThatFollowsItsFirstRead() throws Exception {
        TestGlobal global = new TestGlobal();
        RuntimeInstallationManager manager = new RuntimeInstallationManager(global, ignored -> true, Map::of);
        Path source = Files.createTempDirectory("logyard-same-source-handoff-").resolve("logyard.toml");
        Files.writeString(source, config("info", false), StandardCharsets.UTF_8);
        RuntimeInstallationLease application = manager.acquireApplication(fileRequest(source));
        LogyardLogger logger = application.runtime().logger("example.Service");
        BlockingSnapshotReader snapshots = new BlockingSnapshotReader(source);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RuntimeInstallationLease> handoff =
                    executor.submit(() -> manager.acquireFramework(snapshots.request()));
            assertTrue(snapshots.firstReadCaptured.await(1L, TimeUnit.SECONDS));
            Files.writeString(source, config("debug", false), StandardCharsets.UTF_8);
            snapshots.allowFirstReadReturn.countDown();

            try (RuntimeInstallationLease framework = handoff.get(2L, TimeUnit.SECONDS)) {
                assertTrue(framework.active());
                assertTrue(logger.isDebugEnabled());
                assertFalse(framework.watchesConfiguration());
                assertTrue(snapshots.reads.get() >= 2, "mutable handoff did not perform a final validation read");
            }
        } finally {
            snapshots.allowFirstReadReturn.countDown();
            application.close();
            executor.shutdownNow();
        }
    }

    @Test
    void sameDigestHandoffRepairsAStoppedWatcher() throws Exception {
        Path source = Files.createTempDirectory("logyard-dead-watcher-handoff-").resolve("logyard.toml");
        Files.writeString(source, config("info", true), StandardCharsets.UTF_8);
        ConfigurationInstallationRequest request = fileRequest(source);
        ManagedRuntimeInstallation installation = ManagedRuntimeInstallation.open(request, Map.of());
        try {
            assertTrue(installation.watchesConfiguration());
            stopWatcher(installation);
            assertFalse(installation.watchesConfiguration());

            assertEquals(ReloadResult.APPLIED, installation.reconfigure(request));
            assertTrue(installation.watchesConfiguration());
        } finally {
            installation.close(new TestGlobal()).toCompletableFuture().join();
        }
    }

    @Test
    void immutableSameDigestHandoffRetainsTheReadOnceFastPath() throws Exception {
        TestGlobal global = new TestGlobal();
        RuntimeInstallationManager manager = new RuntimeInstallationManager(global, ignored -> true, Map::of);
        AtomicInteger reads = new AtomicInteger();
        Object identity = new Object();
        ConfigurationInstallationRequest request = new ConfigurationInstallationRequest(
                "immutable",
                null,
                identity,
                () -> {
                    reads.incrementAndGet();
                    return ConfigurationSnapshot.capture(
                            "immutable",
                            Path.of("."),
                            null,
                            config("info", false).getBytes(StandardCharsets.UTF_8));
                });

        RuntimeInstallationLease application = manager.acquireApplication(request);
        try (RuntimeInstallationLease framework = manager.acquireFramework(request)) {
            assertTrue(framework.active());
            assertEquals(2, reads.get(), "immutable handoff performed more than its digest validation read");
        } finally {
            application.close();
        }
    }

    @Test
    void catchUpChangeOverlappingHandoffIsCommittedBeforeActivation() throws Exception {
        TestGlobal global = new TestGlobal();
        RuntimeInstallationManager manager = new RuntimeInstallationManager(global, ignored -> true, Map::of);
        RuntimeInstallationLease application =
                manager.acquireApplication(textRequest("initial", config("info", false)));
        LogyardLogger logger = application.runtime().logger("example.Service");
        Path source = Files.createTempDirectory("logyard-queued-handoff-").resolve("logyard.toml");
        Files.writeString(source, config("error", true), StandardCharsets.UTF_8);
        SecondReadSnapshotReader snapshots = new SecondReadSnapshotReader(source);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RuntimeInstallationLease> handoff =
                    executor.submit(() -> manager.acquireFramework(snapshots.request()));
            assertTrue(snapshots.secondReadCaptured.await(1L, TimeUnit.SECONDS));
            Files.writeString(source, config("debug", true), StandardCharsets.UTF_8);
            snapshots.allowSecondReadReturn.countDown();

            try (RuntimeInstallationLease framework = handoff.get(2L, TimeUnit.SECONDS)) {
                assertTrue(framework.watchesConfiguration());
                assertTrue(logger.isDebugEnabled(), "caught-up configuration was not committed during handoff");
                assertEquals(5, snapshots.reads.get(), "handoff did not perform one bounded stabilization retry");
            }
        } finally {
            snapshots.allowSecondReadReturn.countDown();
            application.close();
            executor.shutdownNow();
        }
    }

    private static void stopWatcher(ManagedRuntimeInstallation installation) throws ReflectiveOperationException {
        Field transitionsField = ManagedRuntimeInstallation.class.getDeclaredField("transitions");
        transitionsField.setAccessible(true);
        RuntimeInstallationTransitions transitions =
                (RuntimeInstallationTransitions) transitionsField.get(installation);
        ActiveRuntimeConfiguration active = transitions.current();
        Field watcherField = ActiveRuntimeConfiguration.class.getDeclaredField("watcher");
        watcherField.setAccessible(true);
        ((ConfigurationWatcher) watcherField.get(active)).close();
    }

    private static ConfigurationInstallationRequest fileRequest(Path source) {
        Path normalized = source.toAbsolutePath().normalize();
        return new ConfigurationInstallationRequest(
                normalized.toString(),
                normalized,
                normalized,
                () -> ConfigurationSnapshot.read(normalized));
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
                reload_debounce = "10ms"
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

    private static final class BlockingSnapshotReader {
        private final Path source;
        private final AtomicInteger reads = new AtomicInteger();
        private final CountDownLatch firstReadCaptured = new CountDownLatch(1);
        private final CountDownLatch allowFirstReadReturn = new CountDownLatch(1);

        private BlockingSnapshotReader(Path source) {
            this.source = source.toAbsolutePath().normalize();
        }

        private ConfigurationInstallationRequest request() {
            return new ConfigurationInstallationRequest(source.toString(), source, source, this::read);
        }

        private ConfigurationSnapshot read() throws IOException {
            ConfigurationSnapshot snapshot = ConfigurationSnapshot.read(source);
            if (reads.incrementAndGet() == 1) {
                firstReadCaptured.countDown();
                await(allowFirstReadReturn);
            }
            return snapshot;
        }
    }

    private static final class SecondReadSnapshotReader {
        private final Path source;
        private final AtomicInteger reads = new AtomicInteger();
        private final CountDownLatch secondReadCaptured = new CountDownLatch(1);
        private final CountDownLatch allowSecondReadReturn = new CountDownLatch(1);

        private SecondReadSnapshotReader(Path source) {
            this.source = source.toAbsolutePath().normalize();
        }

        private ConfigurationInstallationRequest request() {
            return new ConfigurationInstallationRequest(source.toString(), source, source, this::read);
        }

        private ConfigurationSnapshot read() throws IOException {
            ConfigurationSnapshot snapshot = ConfigurationSnapshot.read(source);
            if (reads.incrementAndGet() == 2) {
                secondReadCaptured.countDown();
                await(allowSecondReadReturn);
            }
            return snapshot;
        }
    }

    private static final class TestGlobal implements GlobalRuntimeAccess {
        private final AtomicReference<LogyardRuntime> current = new AtomicReference<>();

        @Override public LogyardRuntime current() { return current.get(); }
        @Override public void install(LogyardRuntime runtime) { current.set(runtime); }

        @Override
        public boolean shutdownIfCurrent(LogyardRuntime runtime) {
            if (!current.compareAndSet(runtime, null)) {
                return false;
            }
            runtime.close();
            return true;
        }
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
