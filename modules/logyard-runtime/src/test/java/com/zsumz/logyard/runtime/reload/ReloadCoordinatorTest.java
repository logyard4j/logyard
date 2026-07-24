package com.zsumz.logyard.runtime.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.runtime.assembly.RuntimeAssembly;
import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import com.zsumz.logyard.runtime.diagnostics.ReloadDiagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ReloadCoordinatorTest {
    @Test
    void unchangedDigestIsNoop() throws Exception {
        Fixture fixture = Fixture.create();
        try (RuntimeBundle bundle = LogyardBootstrap.start(fixture.source())) {
            assertEquals(ReloadResult.UNCHANGED, bundle.reloadNow());
        }
    }

    @Test
    void publishesLevelChangeToExistingLogger() throws Exception {
        Fixture fixture = Fixture.create();
        try (RuntimeBundle bundle = LogyardBootstrap.start(fixture.source())) {
            LogyardLogger logger = bundle.runtime().logger("test.Logger");
            assertTrue(logger.isInfoEnabled());
            fixture.write("error", "4KiB");
            assertEquals(ReloadResult.APPLIED, bundle.reloadNow());
            assertFalse(logger.isInfoEnabled());
            assertTrue(logger.isErrorEnabled());
        }
    }

    @Test
    void invalidTomlRollsBack() throws Exception {
        Fixture fixture = Fixture.create();
        try (RuntimeBundle bundle = LogyardBootstrap.start(fixture.source())) {
            fixture.write("error", "4KiB");
            assertEquals(ReloadResult.APPLIED, bundle.reloadNow());
            Files.writeString(fixture.source(), fixture.config("debug", "4KiB")
                    + "\ninvalid_key = true\n", StandardCharsets.UTF_8);
            assertEquals(ReloadResult.REJECTED, bundle.reloadNow());
            assertEquals(Level.ERROR, bundle.runtime().explain("test.Logger").level());
        }
    }

    @Test
    void watcherWaitsForAnotherFileEventAfterAnInvalidCandidate() throws Exception {
        Fixture fixture = Fixture.create();
        try (ReloadHarness harness = ReloadHarness.start(fixture, ReloadDiagnostics.silent())) {
            Files.writeString(fixture.source(), fixture.config("debug", "4KiB")
                    + "\ninvalid_key = true\n", StandardCharsets.UTF_8);

            assertEquals(WatcherReloadOutcome.WAIT_FOR_CHANGE, harness.coordinator().reloadForWatcher());
            assertEquals(Level.INFO, harness.coordinator().currentConfig().rootLogger().level());
        }
    }

    @Test
    void watcherClassifiesSnapshotReadFailuresAsTransient() throws Exception {
        Fixture fixture = Fixture.create();
        try (ReloadHarness harness = ReloadHarness.start(fixture, ReloadDiagnostics.silent())) {
            ConfigurationSnapshot snapshot = ConfigurationSnapshot.read(fixture.source());
            ReloadCoordinator failingReader = new ReloadCoordinator(
                    fixture.source().toString(),
                    fixture.source(),
                    () -> {
                        throw new IOException("temporarily unavailable");
                    },
                    harness.runtime(),
                    snapshot,
                    harness.coordinator().currentAssembly(),
                    ReloadDiagnostics.silent(),
                    Map.of());

            assertEquals(WatcherReloadOutcome.TRANSIENT_RETRY, failingReader.reloadForWatcher());
            assertEquals(Level.INFO, failingReader.currentConfig().rootLogger().level());
        }
    }

    @Test
    void identicalOutputIsReusedAcrossRouteReload() throws Exception {
        Fixture fixture = Fixture.create();
        try (RuntimeBundle bundle = LogyardBootstrap.start(fixture.source())) {
            fixture.write("warn", "4KiB");
            assertEquals(ReloadResult.APPLIED, bundle.reloadNow());
            assertEquals(Level.WARN, bundle.runtime().explain("test.Logger").level());
        }
    }

    @Test
    void lockedFileMutationIsRejected() throws Exception {
        Fixture fixture = Fixture.create();
        try (RuntimeBundle bundle = LogyardBootstrap.start(fixture.source())) {
            fixture.write("error", "8KiB");
            assertEquals(ReloadResult.REJECTED, bundle.reloadNow());
            assertEquals(Level.INFO, bundle.runtime().explain("test.Logger").level());
        }
    }

    @Test
    void appliedObserverFailureDoesNotChangeCommittedResult() throws Exception {
        Fixture fixture = Fixture.create();
        ReloadDiagnostics diagnostics = new ReloadDiagnostics() {
            @Override
            public void applied(Path source, String previousDigest, String nextDigest) {
                throw new AssertionError("observer failed");
            }
        };
        try (ReloadHarness harness = ReloadHarness.start(fixture, diagnostics)) {
            LogyardLogger logger = harness.runtime().logger("test.Logger");
            fixture.write("error", "4KiB");

            assertEquals(ReloadResult.APPLIED, harness.coordinator().reloadIfChanged());
            assertEquals(Level.ERROR, harness.coordinator().currentConfig().rootLogger().level());
            assertFalse(logger.isInfoEnabled());
            assertTrue(logger.isErrorEnabled());
        }
    }

    @Test
    void unchangedObserverFailureDoesNotChangeResult() throws Exception {
        Fixture fixture = Fixture.create();
        ReloadDiagnostics diagnostics = new ReloadDiagnostics() {
            @Override
            public void unchanged(Path source, String digest) {
                throw new AssertionError("observer failed");
            }
        };
        try (ReloadHarness harness = ReloadHarness.start(fixture, diagnostics)) {
            assertEquals(ReloadResult.UNCHANGED, harness.coordinator().reloadIfChanged());
            assertEquals(Level.INFO, harness.coordinator().currentConfig().rootLogger().level());
        }
    }

    @Test
    void rejectedObserverFailureDoesNotChangeResultOrState() throws Exception {
        Fixture fixture = Fixture.create();
        ReloadDiagnostics diagnostics = new ReloadDiagnostics() {
            @Override
            public void rejected(Path source, Throwable failure) {
                throw new AssertionError("observer failed");
            }
        };
        try (ReloadHarness harness = ReloadHarness.start(fixture, diagnostics)) {
            Files.writeString(fixture.source(), fixture.config("debug", "4KiB")
                    + "\ninvalid_key = true\n", StandardCharsets.UTF_8);

            assertEquals(ReloadResult.REJECTED, harness.coordinator().reloadIfChanged());
            assertEquals(Level.INFO, harness.coordinator().currentConfig().rootLogger().level());
        }
    }

    private record ReloadHarness(DefaultLogyardRuntime runtime, ReloadCoordinator coordinator) implements AutoCloseable {
        static ReloadHarness start(Fixture fixture, ReloadDiagnostics diagnostics) throws Exception {
            ConfigurationSnapshot snapshot = ConfigurationSnapshot.read(fixture.source());
            LogyardConfig config = snapshot.parse(Map.of());
            RuntimeAssembly assembly = LogyardRuntimeFactory.assemble(config, null);
            DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(assembly.plan());
            LogyardRuntimeFactory.attach(runtime, assembly);
            return new ReloadHarness(
                    runtime,
                    new ReloadCoordinator(
                            fixture.source(),
                            runtime,
                            snapshot,
                            assembly,
                            diagnostics,
                            Map.of()));
        }

        @Override
        public void close() {
            runtime.close();
        }
    }

    private record Fixture(Path directory, Path source, Path output) {
        static Fixture create() throws Exception {
            Path directory = Files.createTempDirectory("logyard-reload-test-");
            Fixture fixture = new Fixture(
                    directory,
                    directory.resolve("logyard.toml"),
                    directory.resolve("events.jsonl"));
            fixture.write("info", "4KiB");
            return fixture;
        }

        void write(String level, String buffer) throws Exception {
            Files.writeString(source, config(level, buffer), StandardCharsets.UTF_8);
        }

        String config(String level, String buffer) {
            return """
                    schema = 1
                    [service]
                    name = "test"
                    environment = "test"
                    version = "1"
                    [runtime]
                    shutdown_timeout = "2s"
                    internal_status = "off"
                    [delivery]
                    mode = "sync"
                    capacity = 16
                    [context]
                    [loggers]
                    root = { level = "%s", outputs = ["json"] }
                    [outputs.json]
                    type = "file"
                    path = "%s"
                    append = true
                    buffer = "%s"
                    flush = "0s"
                    """.formatted(level, output.toString().replace("\\", "\\\\"), buffer);
        }
    }
}
