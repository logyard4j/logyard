package com.zsumz.logyard.runtime.installation.process;

import com.zsumz.logyard.runtime.installation.ConfigurationInstallationRequest;
import com.zsumz.logyard.runtime.installation.GlobalRuntimeAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.runtime.assembly.RuntimeAssembly;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RuntimeInstallationManagerTest {
    @Test
    void sharesOneRuntimeAcrossOwnerTypesAndClosesAfterTheFinalLease() {
        Harness harness = Harness.create();
        ConfigurationInstallationRequest request = textRequest("shared", config("info", false), new Object());

        RuntimeInstallationLease application = harness.manager().acquireApplication(request);
        RuntimeAssembly initialAssembly = LogyardRuntimeFactory.assemblyFor(application.runtime());
        RuntimeInstallationLease adapter = harness.manager().acquireAdapter(request);

        assertSame(application.runtime(), adapter.runtime());
        assertSame(initialAssembly, LogyardRuntimeFactory.assemblyFor(adapter.runtime()));
        assertEquals(1, harness.manager().leaseCount(RuntimeOwner.APPLICATION));
        assertEquals(1, harness.manager().leaseCount(RuntimeOwner.ADAPTER));
        assertEquals(1, harness.hooks().get());

        application.close();
        assertTrue(adapter.active());
        assertSame(adapter.runtime(), harness.global().current());

        adapter.close();
        assertFalse(adapter.active());
        assertEquals(null, harness.global().current());
    }

    @Test
    void frameworkHandoffReconfiguresInPlaceAndPreservesExistingLoggers() {
        Harness harness = Harness.create();
        RuntimeInstallationLease adapter = harness.manager().acquireAdapter(textRequest("early-adapter", config("info", false), new Object()));
        LogyardRuntime runtime = adapter.runtime();
        LogyardLogger existing = runtime.logger("example.Service");
        assertFalse(existing.isDebugEnabled());

        RuntimeInstallationLease framework = harness.manager().acquireFramework(textRequest("framework", config("debug", false), new Object()));

        assertSame(runtime, framework.runtime());
        assertSame(existing, framework.runtime().logger("example.Service"));
        assertTrue(existing.isDebugEnabled());

        adapter.close();
        assertTrue(framework.active());
        framework.close();
        assertEquals(null, harness.global().current());
    }

    @Test
    void adapterAcquisitionCannotOverwriteAFrameworkSelectedSource() {
        Harness harness = Harness.create();
        RuntimeInstallationLease framework = harness.manager().acquireFramework(textRequest("framework", config("debug", false), new Object()));
        LogyardLogger logger = framework.runtime().logger("example.Service");
        assertTrue(logger.isDebugEnabled());

        RuntimeInstallationLease adapter = harness.manager().acquireAdapter(textRequest("adapter-default", config("error", false), new Object()));

        assertSame(framework.runtime(), adapter.runtime());
        assertTrue(logger.isDebugEnabled());
        framework.close();
        assertTrue(adapter.active());
        adapter.close();
    }

    @Test
    void invalidFrameworkHandoffLeavesTheCurrentPlanActive() {
        Harness harness = Harness.create();
        RuntimeInstallationLease application = harness.manager().acquireApplication(textRequest("early", config("info", false), new Object()));
        LogyardLogger logger = application.runtime().logger("example.Service");

        assertThrows(
                RuntimeException.class,
                () -> harness.manager().acquireFramework(textRequest("invalid", "schema = 1\nunknown = true\n", new Object())));

        assertTrue(application.active());
        assertTrue(logger.isInfoEnabled());
        assertFalse(logger.isDebugEnabled());
        application.close();
    }

    @Test
    void sourceHandoffReplacesTheActiveReloadSourceAndWatcher() throws Exception {
        Harness harness = Harness.create();
        Path directory = Files.createTempDirectory("logyard-installation-handoff-");
        Path firstPath = directory.resolve("first.toml");
        Path secondPath = directory.resolve("second.toml");
        Files.writeString(firstPath, config("info", true), StandardCharsets.UTF_8);
        Files.writeString(secondPath, config("error", true), StandardCharsets.UTF_8);

        RuntimeInstallationLease application = harness.manager().acquireApplication(fileRequest(firstPath));
        LogyardLogger logger = application.runtime().logger("example.Service");
        RuntimeInstallationLease framework = harness.manager().acquireFramework(fileRequest(secondPath));

        assertTrue(application.watchesConfiguration());
        assertFalse(logger.isInfoEnabled());
        Files.writeString(firstPath, config("debug", true), StandardCharsets.UTF_8);
        assertEquals(ReloadResult.UNCHANGED, application.reloadNow());
        assertFalse(logger.isDebugEnabled());

        Files.writeString(secondPath, config("debug", true), StandardCharsets.UTF_8);
        assertEquals(ReloadResult.APPLIED, framework.reloadNow());
        assertTrue(logger.isDebugEnabled());

        application.close();
        framework.close();
    }

    @Test
    void concurrentAcquisitionsInstallOnceAndRejectRatherThanBlockDuringStartup() throws Exception {
        Harness harness = Harness.create();
        ConfigurationInstallationRequest request = textRequest("concurrent", config("info", false), new Object());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<RuntimeInstallationLease>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    try {
                        return harness.manager().acquireAdapter(request);
                    } catch (IllegalStateException transition) {
                        return null;
                    }
                }));
            }
            List<RuntimeInstallationLease> leases = new ArrayList<>();
            for (Future<RuntimeInstallationLease> future : futures) {
                RuntimeInstallationLease lease = future.get();
                if (lease != null) {
                    leases.add(lease);
                }
            }
            assertFalse(leases.isEmpty());
            LogyardRuntime runtime = leases.getFirst().runtime();
            assertTrue(leases.stream().allMatch(lease -> lease.runtime() == runtime));
            assertEquals(leases.size(), harness.manager().leaseCount(RuntimeOwner.ADAPTER));
            assertEquals(1, harness.hooks().get());

            for (int index = 0; index < leases.size() - 1; index++) {
                leases.get(index).close();
                assertSame(runtime, harness.global().current());
            }
            leases.getLast().close();
            assertEquals(null, harness.global().current());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void adapterBorrowsAnExternalRuntimeWithoutTakingOwnership() {
        Harness harness = Harness.create();
        LogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(event -> {
        });
        harness.global().install(runtime);

        RuntimeInstallationLease adapter = harness.manager().acquireAdapter(textRequest("ignored", config("debug", false), new Object()));

        assertSame(runtime, adapter.runtime());
        assertFalse(adapter.ownsRuntime());
        assertThrows(
                IllegalStateException.class,
                () -> harness.manager().acquireFramework(textRequest("framework", config("debug", false), new Object())));
        adapter.close();
        assertSame(runtime, harness.global().current());
        harness.global().shutdownIfCurrent(runtime);
    }

    private static ConfigurationInstallationRequest textRequest(String description, String text, Object identity) {
        Path base = Path.of(".").toAbsolutePath().normalize();
        return new ConfigurationInstallationRequest(
                description,
                null,
                identity,
                () -> ConfigurationSnapshot.capture(
                        description,
                        base,
                        null,
                        text.getBytes(StandardCharsets.UTF_8)));
    }

    private static ConfigurationInstallationRequest fileRequest(Path source) {
        Path normalized = source.toAbsolutePath().normalize();
        return new ConfigurationInstallationRequest(
                normalized.toString(),
                normalized,
                normalized,
                () -> ConfigurationSnapshot.read(normalized));
    }

    private static String config(String level, boolean watch) {
        return """
                schema = 1
                [runtime]
                watch = %s
                reload_debounce = "30s"
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

    private record Harness(
            RuntimeInstallationManager manager,
            TestGlobalRuntimeAccess global,
            AtomicInteger hooks) {
        static Harness create() {
            TestGlobalRuntimeAccess global = new TestGlobalRuntimeAccess();
            AtomicInteger hooks = new AtomicInteger();
            return new Harness(
                    new RuntimeInstallationManager(global, shutdown -> {
                        hooks.incrementAndGet();
                        return true;
                    }, Map::of),
                    global,
                    hooks);
        }
    }

    private static final class TestGlobalRuntimeAccess implements GlobalRuntimeAccess {
        private final AtomicReference<LogyardRuntime> current = new AtomicReference<>();

        @Override
        public LogyardRuntime current() {
            return current.get();
        }

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
