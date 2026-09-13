package com.logyard4j.logyard.runtime.installation.process;

import com.logyard4j.logyard.runtime.installation.ConfigurationInstallationRequest;
import com.logyard4j.logyard.runtime.installation.GlobalRuntimeAccess;
import com.logyard4j.logyard.runtime.installation.RuntimeInstallation;

import com.logyard4j.logyard.api.Logyard;
import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.api.reload.ReloadResult;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PublicShutdownTransactionTest {
    @Test
    void publicShutdownSupersedesAStartBeforeAnyCountedLeaseCanEscape() throws Exception {
        PublicShutdownBarrierGlobal global = new PublicShutdownBarrierGlobal();
        RuntimeInstallationManager manager = new RuntimeInstallationManager(
                global,
                ignored -> true,
                Map::of,
                (request, environment) -> new TestInstallation());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RuntimeInstallationLease> acquisition =
                    executor.submit(() -> manager.acquireApplication(TestRequests.request()));
            assertTrue(global.installed.await(1L, TimeUnit.SECONDS));

            Future<?> shutdown = executor.submit(Logyard::shutdown);
            assertThrows(TimeoutException.class, () -> shutdown.get(100L, TimeUnit.MILLISECONDS));
            global.allowInstallReturn.countDown();

            ExecutionException superseded =
                    assertThrows(ExecutionException.class, () -> acquisition.get(2L, TimeUnit.SECONDS));
            shutdown.get(2L, TimeUnit.SECONDS);
            assertTrue(superseded.getCause() instanceof RuntimeTransitionInProgressException);
            assertFalse(Logyard.isInitialized());
            assertEquals(0, manager.leaseCount(RuntimeOwner.APPLICATION));

            RuntimeInstallationLease later = manager.acquireApplication(TestRequests.request());
            assertTrue(later.active());
            later.close();
        } finally {
            global.allowInstallReturn.countDown();
            Logyard.shutdown();
            executor.shutdownNow();
        }
    }

    @Test
    void publicShutdownJoinsTheBoundaryOfACloseAlreadyInProgress() throws Exception {
        AtomicReference<Runnable> hook = new AtomicReference<>();
        BlockingCloseInstallation installation = new BlockingCloseInstallation();
        RuntimeInstallationManager manager = new RuntimeInstallationManager(
                new DirectLogyardGlobal(),
                callback -> {
                    hook.set(callback);
                    return true;
                },
                Map::of,
                (request, environment) -> installation);
        RuntimeInstallationLease lease = manager.acquireApplication(TestRequests.request());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> release = executor.submit(lease::close);
            assertTrue(installation.closeEntered.await(1L, TimeUnit.SECONDS));

            Future<?> shutdown = executor.submit(Logyard::shutdown);
            assertThrows(TimeoutException.class, () -> shutdown.get(100L, TimeUnit.MILLISECONDS));
            assertTrue(Logyard.isInitialized());

            installation.allowClose.countDown();
            release.get(2L, TimeUnit.SECONDS);
            shutdown.get(2L, TimeUnit.SECONDS);
            assertFalse(Logyard.isInitialized());
        } finally {
            installation.allowClose.countDown();
            Logyard.shutdown();
            executor.shutdownNow();
        }
    }

    @Test
    void jvmShutdownHookJoinsTheBoundaryOfACloseAlreadyInProgress() throws Exception {
        AtomicReference<Runnable> hook = new AtomicReference<>();
        BlockingCloseInstallation installation = new BlockingCloseInstallation();
        RuntimeInstallationManager manager = new RuntimeInstallationManager(
                new DirectLogyardGlobal(),
                callback -> {
                    hook.set(callback);
                    return true;
                },
                Map::of,
                (request, environment) -> installation);
        RuntimeInstallationLease lease = manager.acquireApplication(TestRequests.request());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> release = executor.submit(lease::close);
            assertTrue(installation.closeEntered.await(1L, TimeUnit.SECONDS));

            Future<?> shutdown = executor.submit(hook.get());
            assertThrows(TimeoutException.class, () -> shutdown.get(100L, TimeUnit.MILLISECONDS));
            assertTrue(Logyard.isInitialized());

            installation.allowClose.countDown();
            release.get(2L, TimeUnit.SECONDS);
            shutdown.get(2L, TimeUnit.SECONDS);
            assertFalse(Logyard.isInitialized());
        } finally {
            installation.allowClose.countDown();
            Logyard.shutdown();
            executor.shutdownNow();
        }
    }

    @Test
    void closeCanInvokePublicShutdownWithoutWaitingOnItsOwnBoundary() {
        ReentrantCloseInstallation installation = new ReentrantCloseInstallation();
        RuntimeInstallationManager manager = new RuntimeInstallationManager(
                new DirectLogyardGlobal(),
                ignored -> true,
                Map::of,
                (request, environment) -> installation);
        RuntimeInstallationLease lease = manager.acquireApplication(TestRequests.request());

        assertTimeoutPreemptively(Duration.ofSeconds(2L), lease::close);

        assertTrue(installation.shutdownReturned.get());
        assertFalse(Logyard.isInitialized());
    }

    private static final class PublicShutdownBarrierGlobal implements GlobalRuntimeAccess {
        private final CountDownLatch installed = new CountDownLatch(1);
        private final CountDownLatch allowInstallReturn = new CountDownLatch(1);
        private final AtomicBoolean firstInstall = new AtomicBoolean(true);

        @Override public LogyardRuntime current() { return Logyard.runtimeOrNull(); }
        @Override public void install(LogyardRuntime runtime) { Logyard.initialize(runtime); }

        @Override
        public void install(LogyardRuntime runtime, BooleanSupplier managedShutdown) {
            Logyard.initializeManaged(runtime, managedShutdown);
            if (firstInstall.compareAndSet(true, false)) {
                installed.countDown();
                await(allowInstallReturn);
            }
        }

        @Override
        public boolean detachIfCurrent(LogyardRuntime runtime) {
            return Logyard.detachManagedIfCurrent(runtime);
        }

        @Override
        public boolean shutdownIfCurrent(LogyardRuntime runtime) {
            return Logyard.releaseManagedIfCurrent(runtime);
        }
    }

    private static final class TestInstallation implements RuntimeInstallation {
        private final LogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(ignored -> {
        });

        @Override public LogyardRuntime runtime() { return runtime; }
        @Override public ReloadResult reconfigure(ConfigurationInstallationRequest request) { return ReloadResult.UNCHANGED; }
        @Override public ReloadResult reloadNow() { return ReloadResult.UNCHANGED; }
        @Override public boolean watchesConfiguration() { return false; }

        @Override
        public java.util.concurrent.CompletionStage<Void> close(GlobalRuntimeAccess globalRuntime) {
            globalRuntime.shutdownIfCurrent(runtime);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class BlockingCloseInstallation implements RuntimeInstallation {
        private final LogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(ignored -> {
        });
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch allowClose = new CountDownLatch(1);

        @Override public LogyardRuntime runtime() { return runtime; }
        @Override public ReloadResult reconfigure(ConfigurationInstallationRequest request) { return ReloadResult.UNCHANGED; }
        @Override public ReloadResult reloadNow() { return ReloadResult.UNCHANGED; }
        @Override public boolean watchesConfiguration() { return false; }

        @Override
        public java.util.concurrent.CompletionStage<Void> close(GlobalRuntimeAccess globalRuntime) {
            closeEntered.countDown();
            await(allowClose);
            globalRuntime.shutdownIfCurrent(runtime);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class ReentrantCloseInstallation implements RuntimeInstallation {
        private final LogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(ignored -> {
        });
        private final AtomicBoolean shutdownReturned = new AtomicBoolean();

        @Override public LogyardRuntime runtime() { return runtime; }
        @Override public ReloadResult reconfigure(ConfigurationInstallationRequest request) { return ReloadResult.UNCHANGED; }
        @Override public ReloadResult reloadNow() { return ReloadResult.UNCHANGED; }
        @Override public boolean watchesConfiguration() { return false; }

        @Override
        public java.util.concurrent.CompletionStage<Void> close(GlobalRuntimeAccess globalRuntime) {
            Logyard.shutdown();
            shutdownReturned.set(true);
            runtime.close();
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class DirectLogyardGlobal implements GlobalRuntimeAccess {
        @Override public LogyardRuntime current() { return Logyard.runtimeOrNull(); }
        @Override public void install(LogyardRuntime runtime) { Logyard.initialize(runtime); }
        @Override public void install(LogyardRuntime runtime, BooleanSupplier shutdown) {
            Logyard.initializeManaged(runtime, shutdown);
        }
        @Override public boolean detachIfCurrent(LogyardRuntime runtime) {
            return Logyard.detachManagedIfCurrent(runtime);
        }
        @Override public boolean shutdownIfCurrent(LogyardRuntime runtime) {
            return Logyard.releaseManagedIfCurrent(runtime);
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
