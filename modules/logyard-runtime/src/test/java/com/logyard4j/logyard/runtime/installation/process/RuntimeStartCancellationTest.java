package com.logyard4j.logyard.runtime.installation.process;

import com.logyard4j.logyard.runtime.installation.ConfigurationInstallationRequest;
import com.logyard4j.logyard.runtime.installation.GlobalRuntimeAccess;
import com.logyard4j.logyard.runtime.installation.RuntimeInstallation;

import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.api.reload.ReloadResult;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeStartCancellationTest {
    @Test
    void terminalShutdownCancelsAndClosesACandidateCreatedAfterTheFactoryReturns() throws Exception {
        TestGlobal global = new TestGlobal();
        AtomicReference<Runnable> shutdown = new AtomicReference<>();
        CountDownLatch secondOpenEntered = new CountDownLatch(1);
        CountDownLatch allowSecondOpen = new CountDownLatch(1);
        AtomicReference<CountingInstallation> second = new AtomicReference<>();
        AtomicInteger opens = new AtomicInteger();
        RuntimeInstallationManager manager = new RuntimeInstallationManager(
                global,
                callback -> {
                    shutdown.set(callback);
                    return true;
                },
                Map::of,
                (request, environment) -> {
                    CountingInstallation installation = new CountingInstallation();
                    if (opens.getAndIncrement() > 0) {
                        second.set(installation);
                        secondOpenEntered.countDown();
                        await(allowSecondOpen);
                    }
                    return installation;
                });
        manager.acquireApplication(TestRequests.request()).close();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RuntimeInstallationLease> acquisition =
                    executor.submit(() -> manager.acquireApplication(TestRequests.request()));
            assertTrue(secondOpenEntered.await(1L, TimeUnit.SECONDS));
            Future<?> terminalShutdown = executor.submit(shutdown.get());
            awaitCancellation(manager);
            allowSecondOpen.countDown();

            assertStartCancelled(acquisition);
            terminalShutdown.get(2L, TimeUnit.SECONDS);
            assertEquals(1, second.get().closeCalls.get());
            assertNull(global.current());
            assertTerminated(manager);
        } finally {
            allowSecondOpen.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void terminalShutdownRollsBackAGlobalCandidatePublishedBeforeHookRegistrationReturns() throws Exception {
        TestGlobal global = new TestGlobal();
        AtomicReference<Runnable> shutdown = new AtomicReference<>();
        CountDownLatch hookRegistrationEntered = new CountDownLatch(1);
        CountDownLatch allowHookRegistration = new CountDownLatch(1);
        CountingInstallation candidate = new CountingInstallation();
        RuntimeInstallationManager manager = new RuntimeInstallationManager(
                global,
                callback -> {
                    shutdown.set(callback);
                    hookRegistrationEntered.countDown();
                    await(allowHookRegistration);
                    return true;
                },
                Map::of,
                (request, environment) -> candidate);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RuntimeInstallationLease> acquisition =
                    executor.submit(() -> manager.acquireApplication(TestRequests.request()));
            assertTrue(hookRegistrationEntered.await(1L, TimeUnit.SECONDS));
            Future<?> terminalShutdown = executor.submit(shutdown.get());
            awaitCancellation(manager);
            allowHookRegistration.countDown();

            assertStartCancelled(acquisition);
            terminalShutdown.get(2L, TimeUnit.SECONDS);
            assertEquals(1, candidate.closeCalls.get());
            assertNull(global.current());
            assertTerminated(manager);
        } finally {
            allowHookRegistration.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void terminalShutdownWaitsForTheCloseBoundaryButNotFinalAsynchronousRetirement() throws Exception {
        TestGlobal global = new TestGlobal();
        AtomicReference<Runnable> shutdown = new AtomicReference<>();
        CountDownLatch secondOpenEntered = new CountDownLatch(1);
        CountDownLatch allowSecondOpen = new CountDownLatch(1);
        CompletableFuture<Void> finalRetirement = new CompletableFuture<>();
        AtomicReference<CountingInstallation> second = new AtomicReference<>();
        AtomicInteger opens = new AtomicInteger();
        RuntimeInstallationManager manager = new RuntimeInstallationManager(
                global,
                callback -> {
                    shutdown.set(callback);
                    return true;
                },
                Map::of,
                (request, environment) -> {
                    CountingInstallation installation = opens.getAndIncrement() == 0
                            ? new CountingInstallation()
                            : new CountingInstallation(finalRetirement);
                    if (opens.get() > 1) {
                        second.set(installation);
                        secondOpenEntered.countDown();
                        await(allowSecondOpen);
                    }
                    return installation;
                });
        manager.acquireApplication(TestRequests.request()).close();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RuntimeInstallationLease> acquisition =
                    executor.submit(() -> manager.acquireApplication(TestRequests.request()));
            assertTrue(secondOpenEntered.await(1L, TimeUnit.SECONDS));
            Future<?> terminalShutdown = executor.submit(shutdown.get());
            awaitCancellation(manager);
            allowSecondOpen.countDown();

            assertStartCancelled(acquisition);
            terminalShutdown.get(2L, TimeUnit.SECONDS);
            assertEquals(1, second.get().closeCalls.get());
            assertNull(global.current());
            IllegalStateException closing = assertThrows(
                    IllegalStateException.class,
                    () -> manager.acquireAdapter(TestRequests.request()));
            assertTrue(closing.getMessage().contains("closing"));

            finalRetirement.complete(null);
            awaitTerminated(manager);
        } finally {
            allowSecondOpen.countDown();
            finalRetirement.complete(null);
            executor.shutdownNow();
        }
    }

    private static void assertStartCancelled(Future<RuntimeInstallationLease> acquisition) {
        ExecutionException failure =
                assertThrows(ExecutionException.class, () -> acquisition.get(2L, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof RuntimeTransitionInProgressException);
    }

    private static void assertTerminated(RuntimeInstallationManager manager) {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> manager.acquireAdapter(TestRequests.request()));
        assertTrue(failure.getMessage().contains("terminated"));
    }

    private static void awaitTerminated(RuntimeInstallationManager manager) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        while (true) {
            try {
                assertTerminated(manager);
                return;
            } catch (AssertionError notFinished) {
                if (System.nanoTime() >= deadline) {
                    throw notFinished;
                }
                Thread.sleep(1L);
            }
        }
    }

    private static void awaitCancellation(RuntimeInstallationManager manager) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        while (!manager.startCancellationPending()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("terminal shutdown did not cancel the pending start");
            }
            Thread.sleep(1L);
        }
    }

    private static class TestGlobal implements GlobalRuntimeAccess {
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

    private static final class CountingInstallation implements RuntimeInstallation {
        private final LogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(ignored -> {
        });
        private final CompletableFuture<Void> finalRetirement;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private CountingInstallation() {
            this(CompletableFuture.completedFuture(null));
        }

        private CountingInstallation(CompletableFuture<Void> finalRetirement) {
            this.finalRetirement = finalRetirement;
        }

        @Override public LogyardRuntime runtime() { return runtime; }
        @Override public ReloadResult reconfigure(ConfigurationInstallationRequest request) { return ReloadResult.UNCHANGED; }
        @Override public ReloadResult reloadNow() { return ReloadResult.UNCHANGED; }
        @Override public boolean watchesConfiguration() { return false; }

        @Override
        public java.util.concurrent.CompletionStage<Void> close(GlobalRuntimeAccess globalRuntime) {
            closeCalls.incrementAndGet();
            if (!globalRuntime.shutdownIfCurrent(runtime)) {
                runtime.close();
            }
            return finalRetirement;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("start cancellation barrier interrupted", interrupted);
        }
    }
}
