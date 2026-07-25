package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            assertFalse(shutdown.isDone());
            awaitCancellation(manager);
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test barrier interrupted", interrupted);
        }
    }

    private static void awaitCancellation(RuntimeInstallationManager manager) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        while (!manager.startCancellationPending()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("public shutdown did not cancel the pending start");
            }
            Thread.sleep(1L);
        }
    }
}
