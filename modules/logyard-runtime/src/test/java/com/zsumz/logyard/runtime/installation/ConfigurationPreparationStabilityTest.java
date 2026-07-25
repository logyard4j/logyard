package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.runtime.diagnostics.StderrReloadDiagnostics;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;
import com.zsumz.logyard.runtime.reload.WatcherReloadOutcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigurationPreparationStabilityTest {
    @Test
    void thirdReadCanDisableWatchingBeforeInitialInstallationCommits() throws Exception {
        String watching = config("info", true, "25ms");
        String disabled = config("info", false, "25ms");
        ScriptedSnapshots snapshots = ScriptedSnapshots.sequence(watching, watching, disabled, disabled, disabled);

        try (RuntimeInstallationLease lease = manager().acquireApplication(snapshots.request())) {
            assertFalse(lease.watchesConfiguration());
            assertEquals(5, snapshots.reads());
        }
    }

    @Test
    void thirdReadCanEnableWatchingBeforeInitialInstallationCommits() throws Exception {
        String disabled = config("info", false, "25ms");
        String watching = config("info", true, "25ms");
        ScriptedSnapshots snapshots = ScriptedSnapshots.sequence(disabled, disabled, watching, watching, watching);

        try (RuntimeInstallationLease lease = manager().acquireApplication(snapshots.request())) {
            assertTrue(lease.watchesConfiguration());
            assertEquals(5, snapshots.reads());
        }
    }

    @Test
    void thirdReadRebuildsTheCompleteWatcherPolicy() throws Exception {
        String initial = config("info", true, "5s", "2s", "off");
        String caughtUp = config("info", true, "10ms", "3s", "warn");
        ScriptedSnapshots snapshots = ScriptedSnapshots.sequence(initial, initial, caughtUp, caughtUp, caughtUp);
        ConfigurationInstallationRequest request = snapshots.request();
        PreparedRuntimeConfiguration prepared = PreparedRuntimeConfiguration.prepare(
                request,
                PreparedRuntimeConfiguration.read(request),
                null,
                Map.of(),
                () -> WatcherReloadOutcome.WAIT_FOR_CHANGE);
        IllegalStateException cleanup = new IllegalStateException("test cleanup");
        try {
            assertEquals(Duration.ofMillis(10L), prepared.watcherPolicy().debounce());
            assertEquals(Duration.ofSeconds(3L), prepared.watcherPolicy().closeTimeout());
            assertInstanceOf(StderrReloadDiagnostics.class, prepared.watcherPolicy().diagnostics());
            assertEquals(5, snapshots.reads());
        } finally {
            prepared.closeWatcher(cleanup);
            prepared.assembly().closeCandidateOutputs(null, cleanup);
        }
    }

    @Test
    void thirdReadRoutingChangeIsCommittedWithoutPostActivationReload() throws Exception {
        String initial = config("info", true, "25ms");
        String caughtUp = config("debug", true, "25ms");
        ScriptedSnapshots snapshots = ScriptedSnapshots.sequence(initial, initial, caughtUp, caughtUp, caughtUp);

        try (RuntimeInstallationLease lease = manager().acquireApplication(snapshots.request())) {
            LogyardLogger logger = lease.runtime().logger("example.Service");
            assertTrue(logger.isDebugEnabled());
            assertEquals(5, snapshots.reads());
        }
    }

    @Test
    void continuouslyChangingSourceFailsAtTheBoundedStabilizationLimit() throws Exception {
        ScriptedSnapshots snapshots = ScriptedSnapshots.generate(
                index -> config("info", true, "25ms") + "\n# revision " + index + '\n');

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> manager().acquireApplication(snapshots.request()));

        assertTrue(failure.getMessage().contains("did not stabilize after 8 preparation attempts"));
        assertEquals(17, snapshots.reads());
    }

    @Test
    void frameworkHandoffUsesTheSameThirdReadStabilizationGuarantee() throws Exception {
        RuntimeInstallationManager manager = manager();
        RuntimeInstallationLease application =
                manager.acquireApplication(immutableRequest("application", config("info", false, "25ms")));
        LogyardLogger logger = application.runtime().logger("example.Service");
        String provisional = config("debug", true, "25ms");
        String finalSnapshot = config("debug", false, "25ms");
        ScriptedSnapshots snapshots =
                ScriptedSnapshots.sequence(provisional, provisional, finalSnapshot, finalSnapshot, finalSnapshot);
        try {
            try (RuntimeInstallationLease framework = manager.acquireFramework(snapshots.request())) {
                assertTrue(framework.active());
                assertTrue(logger.isDebugEnabled());
                assertFalse(framework.watchesConfiguration());
                assertEquals(5, snapshots.reads());
            }
        } finally {
            application.close();
        }
    }

    private static RuntimeInstallationManager manager() {
        return new RuntimeInstallationManager(new TestGlobal(), ignored -> true, Map::of);
    }

    private static ConfigurationInstallationRequest immutableRequest(String description, String text) {
        return new ConfigurationInstallationRequest(
                description,
                null,
                new Object(),
                () -> snapshot(description, null, text));
    }

    private static ConfigurationSnapshot snapshot(String description, Path watchPath, String text) throws IOException {
        return ConfigurationSnapshot.capture(
                description,
                Path.of(".").toAbsolutePath(),
                watchPath,
                text.getBytes(StandardCharsets.UTF_8));
    }

    private static String config(String level, boolean watch, String debounce) {
        return config(level, watch, debounce, "2s", "off");
    }

    private static String config(String level, boolean watch, String debounce, String shutdownTimeout, String internalStatus) {
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

    private static final class ScriptedSnapshots {
        private final Path source;
        private final IntFunction<String> content;
        private final AtomicInteger reads = new AtomicInteger();

        private ScriptedSnapshots(IntFunction<String> content) throws IOException {
            source = Files.createTempDirectory("logyard-stabilization-").resolve("logyard.toml");
            Files.writeString(source, "provisional", StandardCharsets.UTF_8);
            this.content = content;
        }

        static ScriptedSnapshots sequence(String... snapshots) throws IOException {
            List<String> sequence = List.of(snapshots);
            return new ScriptedSnapshots(index -> sequence.get(Math.min(index, sequence.size() - 1)));
        }

        static ScriptedSnapshots generate(IntFunction<String> snapshots) throws IOException {
            return new ScriptedSnapshots(snapshots);
        }

        ConfigurationInstallationRequest request() {
            return new ConfigurationInstallationRequest(source.toString(), source, source, this::read);
        }

        int reads() {
            return reads.get();
        }

        private ConfigurationSnapshot read() throws IOException {
            int index = reads.getAndIncrement();
            return snapshot(source.toString(), source, content.apply(index));
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
