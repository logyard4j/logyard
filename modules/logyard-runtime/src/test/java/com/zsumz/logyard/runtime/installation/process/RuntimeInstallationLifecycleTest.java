package com.zsumz.logyard.runtime.installation.process;

import com.zsumz.logyard.runtime.installation.ConfigurationInstallationRequest;
import com.zsumz.logyard.runtime.installation.GlobalRuntimeAccess;
import com.zsumz.logyard.runtime.installation.RuntimeInstallation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RuntimeInstallationLifecycleTest {
    @Test
    void recursiveAcquisitionDuringProviderCreationFailsFastWithoutInstallingANestedRuntime() {
        TestGlobal global = new TestGlobal();
        AtomicReference<RuntimeInstallationManager> managerReference = new AtomicReference<>();
        AtomicInteger opens = new AtomicInteger();
        RuntimeInstallationFactory factory = (request, environment) -> {
            if (opens.getAndIncrement() == 0) {
                managerReference.get().acquireAdapter(request);
            }
            return new StubInstallation();
        };
        RuntimeInstallationManager manager = manager(global, factory);
        managerReference.set(manager);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> manager.acquireApplication(request()));

        assertTrue(failure.getMessage().contains("starting"));
        assertNull(global.current());
        try (RuntimeInstallationLease lease = manager.acquireApplication(request())) {
            assertTrue(lease.active());
        }
        assertNull(global.current());
    }

    @Test
    void helperThreadAcquisitionDuringProviderCreationDoesNotWaitForTheProvider() throws Exception {
        TestGlobal global = new TestGlobal();
        AtomicReference<RuntimeInstallationManager> managerReference = new AtomicReference<>();
        ExecutorService helper = Executors.newSingleThreadExecutor();
        RuntimeInstallationFactory factory = (request, environment) -> {
            Future<RuntimeInstallationLease> nested = helper.submit(() -> managerReference.get().acquireAdapter(request));
            ExecutionException failure = assertThrows(ExecutionException.class, () -> nested.get());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            return new StubInstallation();
        };
        RuntimeInstallationManager manager = manager(global, factory);
        managerReference.set(manager);
        try {
            try (RuntimeInstallationLease lease = manager.acquireApplication(request())) {
                assertTrue(lease.active());
                assertTrue(global.current() != null);
            }
        } finally {
            helper.shutdownNow();
        }
    }

    @Test
    void closeCallbacksFailFastAndTheManagerRemainsClosingUntilRetirementCompletes() throws Exception {
        TestGlobal global = new TestGlobal();
        AtomicReference<RuntimeInstallationManager> managerReference = new AtomicReference<>();
        ExecutorService helper = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> retirement = new CompletableFuture<>();
        StubInstallation first = new StubInstallation(retirement, access -> {
            IllegalStateException sameThread = assertThrows(
                    IllegalStateException.class,
                    () -> managerReference.get().acquireAdapter(request()));
            assertTrue(sameThread.getMessage().contains("closing"));
            Future<RuntimeInstallationLease> nested = helper.submit(() -> managerReference.get().acquireAdapter(request()));
            ExecutionException helperFailure = assertThrows(ExecutionException.class, () -> nested.get());
            assertInstanceOf(IllegalStateException.class, helperFailure.getCause());
        });
        AtomicInteger opens = new AtomicInteger();
        RuntimeInstallationManager manager = manager(global, (request, environment) ->
                opens.getAndIncrement() == 0 ? first : new StubInstallation());
        managerReference.set(manager);
        try {
            RuntimeInstallationLease lease = manager.acquireApplication(request());
            lease.close();

            assertNull(global.current());
            assertThrows(IllegalStateException.class, () -> manager.acquireAdapter(request()));
            retirement.complete(null);
            await(Duration.ofSeconds(1), () -> manager.acquireAdapter(request()).close());
            assertEquals(2, opens.get());
        } finally {
            helper.shutdownNow();
        }
    }

    private static RuntimeInstallationManager manager(TestGlobal global, RuntimeInstallationFactory factory) {
        return new RuntimeInstallationManager(global, ignored -> {
            return true;
        }, Map::of, factory);
    }

    private static ConfigurationInstallationRequest request() {
        String config = "schema = 1\n";
        return new ConfigurationInstallationRequest(
                "test",
                null,
                new Object(),
                () -> com.zsumz.logyard.runtime.reload.ConfigurationSnapshot.capture(
                        "test",
                        Path.of(".").toAbsolutePath(),
                        null,
                        config.getBytes(StandardCharsets.UTF_8)));
    }

    private static void await(Duration timeout, Runnable assertion) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (IllegalStateException transition) {
                if (System.nanoTime() >= deadline) {
                    throw transition;
                }
                Thread.sleep(1);
            }
        }
    }

    private static final class StubInstallation implements RuntimeInstallation {
        private final LogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(ignored -> {
        });
        private final CompletableFuture<Void> retirement;
        private final java.util.function.Consumer<GlobalRuntimeAccess> closeCallback;

        private StubInstallation() {
            this(CompletableFuture.completedFuture(null), ignored -> {
            });
        }

        private StubInstallation(
                CompletableFuture<Void> retirement,
                java.util.function.Consumer<GlobalRuntimeAccess> closeCallback) {
            this.retirement = retirement;
            this.closeCallback = closeCallback;
        }

        @Override
        public LogyardRuntime runtime() {
            return runtime;
        }

        @Override
        public ReloadResult reconfigure(ConfigurationInstallationRequest request) {
            return ReloadResult.UNCHANGED;
        }

        @Override
        public ReloadResult reloadNow() {
            return ReloadResult.UNCHANGED;
        }

        @Override
        public boolean watchesConfiguration() {
            return false;
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> close(GlobalRuntimeAccess globalRuntime) {
            closeCallback.accept(globalRuntime);
            globalRuntime.shutdownIfCurrent(runtime);
            return retirement;
        }
    }

    private static final class TestGlobal implements GlobalRuntimeAccess {
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
