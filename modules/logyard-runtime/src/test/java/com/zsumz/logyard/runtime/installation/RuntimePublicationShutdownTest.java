package com.zsumz.logyard.runtime.installation;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimePublicationShutdownTest {
    @Test
    void terminalShutdownRollsBackACandidateWhileGlobalPublicationIsInProgress() throws Exception {
        BarrierGlobal global = new BarrierGlobal();
        AtomicReference<Runnable> shutdown = new AtomicReference<>();
        AtomicInteger opens = new AtomicInteger();
        AtomicReference<CountingInstallation> candidate = new AtomicReference<>();
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
                        candidate.set(installation);
                    }
                    return installation;
                });
        manager.acquireApplication(TestRequests.request()).close();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RuntimeInstallationLease> acquisition =
                    executor.submit(() -> manager.acquireApplication(TestRequests.request()));
            assertTrue(global.publicationEntered.await(1L, TimeUnit.SECONDS));
            Future<?> terminalShutdown = executor.submit(shutdown.get());
            awaitCancellation(manager);
            global.allowPublicationReturn.countDown();

            ExecutionException cancellation =
                    assertThrows(ExecutionException.class, () -> acquisition.get(2L, TimeUnit.SECONDS));
            assertTrue(cancellation.getCause() instanceof RuntimeTransitionInProgressException);
            terminalShutdown.get(2L, TimeUnit.SECONDS);
            assertEquals(1, candidate.get().closeCalls.get());
            assertNull(global.current());
            assertTerminated(manager);
        } finally {
            global.allowPublicationReturn.countDown();
            executor.shutdownNow();
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

    private static void assertTerminated(RuntimeInstallationManager manager) {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> manager.acquireAdapter(TestRequests.request()));
        assertTrue(failure.getMessage().contains("terminated"));
    }

    private static final class BarrierGlobal implements GlobalRuntimeAccess {
        private final AtomicReference<LogyardRuntime> current = new AtomicReference<>();
        private final AtomicInteger publications = new AtomicInteger();
        private final CountDownLatch publicationEntered = new CountDownLatch(1);
        private final CountDownLatch allowPublicationReturn = new CountDownLatch(1);

        @Override public LogyardRuntime current() { return current.get(); }

        @Override
        public void install(LogyardRuntime runtime) {
            if (!current.compareAndSet(null, runtime)) {
                throw new IllegalStateException("runtime already installed");
            }
            if (publications.incrementAndGet() == 2) {
                publicationEntered.countDown();
                await(allowPublicationReturn);
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
        private final AtomicInteger closeCalls = new AtomicInteger();

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
            return CompletableFuture.completedFuture(null);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("publication barrier interrupted", interrupted);
        }
    }
}
