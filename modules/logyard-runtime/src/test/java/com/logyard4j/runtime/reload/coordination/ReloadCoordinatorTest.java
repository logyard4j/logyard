package com.logyard4j.runtime.reload.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.api.Level;
import com.logyard4j.api.LogyardLogger;
import com.logyard4j.api.reload.ReloadResult;
import com.logyard4j.config.LogyardConfig;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.core.runtime.RuntimeReloadDeferredException;
import com.logyard4j.runtime.assembly.LogyardRuntimeFactory;
import com.logyard4j.runtime.assembly.RuntimeAssembly;
import com.logyard4j.runtime.bootstrap.LogyardBootstrap;
import com.logyard4j.runtime.bootstrap.RuntimeBundle;
import com.logyard4j.runtime.diagnostics.ReloadDiagnostics;
import com.logyard4j.runtime.reload.ConfigurationSnapshot;
import com.logyard4j.runtime.reload.ConfigurationInputs;
import com.logyard4j.runtime.reload.WatcherReloadOutcome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

            assertEquals(WatcherReloadOutcome.INVALID_CANDIDATE, harness.coordinator().reloadForWatcher());
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
                    ConfigurationInputs.capture(Map.of()));

            assertEquals(WatcherReloadOutcome.TRANSIENT_RETRY, failingReader.reloadForWatcher());
            assertEquals(Level.INFO, failingReader.currentConfig().rootLogger().level());
        }
    }

    @Test
    void watcherRetainsAValidCandidateUntilTransientRuntimeCapacityRecovers() throws Exception {
        Fixture fixture = Fixture.create();
        try (ReloadHarness harness = ReloadHarness.start(fixture, ReloadDiagnostics.silent())) {
            ConfigurationSnapshot snapshot = ConfigurationSnapshot.read(fixture.source());
            AtomicInteger publications = new AtomicInteger();
            ReloadCoordinator coordinator = new ReloadCoordinator(
                    fixture.source().toString(),
                    fixture.source(),
                    () -> ConfigurationSnapshot.read(fixture.source()),
                    harness.runtime(),
                    plan -> {
                        if (publications.getAndIncrement() == 0) {
                            throw new RuntimeReloadDeferredException("retirement capacity is temporarily exhausted");
                        }
                        harness.runtime().reload(plan);
                    },
                    snapshot,
                    harness.coordinator().currentAssembly(),
                    ReloadDiagnostics.silent(),
                    ConfigurationInputs.capture(Map.of()));
            fixture.write("debug", "4KiB");

            assertEquals(WatcherReloadOutcome.BUSY_RETRY, coordinator.reloadForWatcher());
            assertEquals(Level.INFO, coordinator.currentConfig().rootLogger().level());
            assertEquals(WatcherReloadOutcome.APPLIED, coordinator.reloadForWatcher());
            assertEquals(Level.DEBUG, coordinator.currentConfig().rootLogger().level());
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
    void rejectsEveryInstallationOwnedRuntimePolicyChange() throws Exception {
        Fixture fixture = Fixture.create();
        try (ReloadHarness harness = ReloadHarness.start(fixture, ReloadDiagnostics.silent())) {
            String baseline = fixture.config("debug", "4KiB");
            String[] candidates = {
                    baseline.replace("shutdown_timeout = \"2s\"", "shutdown_timeout = \"3s\""),
                    baseline.replace("internal_status = \"off\"", "internal_status = \"warn\""),
                    baseline.replace("[runtime]", "[runtime]\nwatch = true"),
                    baseline.replace("[runtime]", "[runtime]\nreload_debounce = \"1s\"")
            };

            for (String candidate : candidates) {
                Files.writeString(fixture.source(), candidate, StandardCharsets.UTF_8);
                assertEquals(ReloadResult.REJECTED, harness.coordinator().reloadIfChanged());
                assertEquals(Level.INFO, harness.coordinator().currentConfig().rootLogger().level());
            }
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
            assembly.activateCandidateOutputs();
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
