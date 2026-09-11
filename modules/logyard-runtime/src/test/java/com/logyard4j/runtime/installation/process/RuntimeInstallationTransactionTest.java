package com.logyard4j.runtime.installation.process;

import com.logyard4j.runtime.installation.ConfigurationInstallationRequest;
import com.logyard4j.runtime.installation.GlobalRuntimeAccess;
import com.logyard4j.runtime.installation.RuntimeInstallation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.api.reload.ReloadResult;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RuntimeInstallationTransactionTest {
    @Test
    void staleGlobalObservationCannotRetireALeaseReturnedToAnotherThread() throws Exception {
        BarrierGlobal global = new BarrierGlobal();
        RuntimeInstallationManager manager = manager(global, (request, environment) -> new TestInstallation());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RuntimeInstallationLease> delayed = executor.submit(() -> {
                global.delayCurrentReadOn(Thread.currentThread());
                return manager.acquireApplication(TestRequests.request());
            });
            assertTrue(global.snapshotRead.await(1, TimeUnit.SECONDS));

            Future<RuntimeInstallationLease> concurrent =
                    executor.submit(() -> acquireAfterTransition(manager));
            global.installObserved.await(100, TimeUnit.MILLISECONDS);
            global.releaseSnapshot.countDown();

            try (RuntimeInstallationLease first = delayed.get(2, TimeUnit.SECONDS);
                    RuntimeInstallationLease second = concurrent.get(2, TimeUnit.SECONDS)) {
                assertSame(first.runtime(), second.runtime());
                assertTrue(first.active());
                assertTrue(second.active());
            }
            assertEquals(null, global.current());
        } finally {
            global.releaseSnapshot.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectedReconfigurationRollsBackTheReservedLeaseAndAuthority() {
        TestGlobal global = new TestGlobal();
        TestInstallation installation = new TestInstallation();
        installation.reconfigurationResult = ReloadResult.REJECTED;
        RuntimeInstallationManager manager = manager(global, (request, environment) -> installation);
        RuntimeInstallationLease application = manager.acquireApplication(TestRequests.request());

        RuntimeTransitionInProgressException failure = assertThrows(
                RuntimeTransitionInProgressException.class,
                () -> manager.acquireFramework(TestRequests.request()));

        assertTrue(failure.getMessage().contains("retry"));
        assertTrue(application.active());
        assertEquals(1, manager.leaseCount(RuntimeOwner.APPLICATION));
        assertEquals(0, manager.leaseCount(RuntimeOwner.FRAMEWORK));
        application.close();
    }

    @Test
    void rejectedReconfigurationRetiresTheRuntimeWhenThePriorOwnerReleasesDuringHandoff() throws Exception {
        TestGlobal global = new TestGlobal();
        BlockingRejectedInstallation installation = new BlockingRejectedInstallation();
        RuntimeInstallationManager manager = manager(global, (request, environment) -> installation);
        RuntimeInstallationLease application = manager.acquireApplication(TestRequests.request());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RuntimeTransitionInProgressException> rejected = executor.submit(() -> assertThrows(
                    RuntimeTransitionInProgressException.class,
                    () -> manager.acquireFramework(TestRequests.request())));
            assertTrue(installation.reconfigurationEntered.await(1, TimeUnit.SECONDS));

            application.close();
            installation.allowReconfiguration.countDown();

            assertTrue(rejected.get(2, TimeUnit.SECONDS).getMessage().contains("retry"));
            assertEquals(0, manager.leaseCount(RuntimeOwner.APPLICATION));
            assertEquals(0, manager.leaseCount(RuntimeOwner.FRAMEWORK));
            assertEquals(null, global.current());
        } finally {
            installation.allowReconfiguration.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedShutdownHookRegistrationIsRetriedByTheNextInstallation() {
        TestGlobal global = new TestGlobal();
        AtomicInteger attempts = new AtomicInteger();
        RuntimeInstallationManager manager = new RuntimeInstallationManager(
                global,
                ignored -> attempts.incrementAndGet() > 1,
                Map::of,
                (request, environment) -> new TestInstallation());

        manager.acquireApplication(TestRequests.request()).close();
        manager.acquireApplication(TestRequests.request()).close();

        assertEquals(2, attempts.get());
    }

    @Test
    void processShutdownIsTerminalEvenAfterResourcesFinishRetiring() {
        TestGlobal global = new TestGlobal();
        AtomicReference<Runnable> shutdown = new AtomicReference<>();
        RuntimeInstallationManager manager = new RuntimeInstallationManager(
                global,
                callback -> {
                    shutdown.set(callback);
                    return true;
                },
                Map::of,
                (request, environment) -> new TestInstallation());
        RuntimeInstallationLease lease = manager.acquireApplication(TestRequests.request());

        shutdown.get().run();

        assertFalse(lease.active());
        assertEquals(null, global.current());
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> manager.acquireAdapter(TestRequests.request()));
        assertTrue(failure.getMessage().contains("terminated"));
    }

    private static RuntimeInstallationManager manager(
            GlobalRuntimeAccess global,
            RuntimeInstallationFactory factory) {
        return new RuntimeInstallationManager(global, ignored -> true, Map::of, factory);
    }

    private static RuntimeInstallationLease acquireAfterTransition(RuntimeInstallationManager manager)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
            try {
                return manager.acquireApplication(TestRequests.request());
            } catch (RuntimeTransitionInProgressException transition) {
                if (System.nanoTime() >= deadline) {
                    throw transition;
                }
                Thread.sleep(1);
            }
        }
    }

    private static class TestGlobal implements GlobalRuntimeAccess {
        protected final AtomicReference<LogyardRuntime> current = new AtomicReference<>();

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

    private static final class BarrierGlobal extends TestGlobal {
        private final CountDownLatch snapshotRead = new CountDownLatch(1);
        private final CountDownLatch releaseSnapshot = new CountDownLatch(1);
        private final CountDownLatch installObserved = new CountDownLatch(1);
        private final AtomicReference<Thread> delayedThread = new AtomicReference<>();
        private final AtomicBoolean delayed = new AtomicBoolean();

        void delayCurrentReadOn(Thread thread) {
            delayedThread.set(thread);
        }

        @Override
        public LogyardRuntime current() {
            LogyardRuntime observed = super.current();
            if (Thread.currentThread() == delayedThread.get() && delayed.compareAndSet(false, true)) {
                snapshotRead.countDown();
                await(releaseSnapshot);
            }
            return observed;
        }

        @Override
        public void install(LogyardRuntime runtime) {
            super.install(runtime);
            installObserved.countDown();
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("barrier interrupted", interrupted);
            }
        }
    }

    private static class TestInstallation implements RuntimeInstallation {
        private final LogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(ignored -> {
        });
        private ReloadResult reconfigurationResult = ReloadResult.UNCHANGED;

        @Override public LogyardRuntime runtime() { return runtime; }
        @Override public ReloadResult reconfigure(ConfigurationInstallationRequest request) { return reconfigurationResult; }
        @Override public ReloadResult reloadNow() { return ReloadResult.UNCHANGED; }
        @Override public boolean watchesConfiguration() { return false; }

        @Override
        public java.util.concurrent.CompletionStage<Void> close(GlobalRuntimeAccess globalRuntime) {
            globalRuntime.shutdownIfCurrent(runtime);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class BlockingRejectedInstallation extends TestInstallation {
        private final CountDownLatch reconfigurationEntered = new CountDownLatch(1);
        private final CountDownLatch allowReconfiguration = new CountDownLatch(1);

        @Override
        public ReloadResult reconfigure(ConfigurationInstallationRequest request) {
            reconfigurationEntered.countDown();
            BarrierGlobal.await(allowReconfiguration);
            return ReloadResult.REJECTED;
        }
    }
}
