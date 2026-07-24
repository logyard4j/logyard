package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeInstallationFastPathTest {
    @Test
    void activeLeaseCheckDoesNotEnterTheManagerMonitor() throws Exception {
        RuntimeInstallationManager manager = new RuntimeInstallationManager(
                new TestGlobal(),
                ignored -> true,
                Map::of,
                (request, environment) -> new TestInstallation());
        RuntimeInstallationLease lease = manager.acquireAdapter(TestRequests.request());
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch releaseMonitor = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> {
                synchronized (manager) {
                    monitorHeld.countDown();
                    await(releaseMonitor);
                }
            });
            assertTrue(monitorHeld.await(1L, TimeUnit.SECONDS));

            long started = System.nanoTime();
            assertTrue(lease.active());
            assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(250L));

            releaseMonitor.countDown();
            holder.get(2L, TimeUnit.SECONDS);
        } finally {
            releaseMonitor.countDown();
            lease.close();
            executor.shutdownNow();
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

    private static final class TestGlobal implements GlobalRuntimeAccess {
        private final AtomicReference<LogyardRuntime> current = new AtomicReference<>();

        @Override public LogyardRuntime current() { return current.get(); }
        @Override public void install(LogyardRuntime runtime) { current.set(runtime); }

        @Override
        public boolean shutdownIfCurrent(LogyardRuntime runtime) {
            if (!current.compareAndSet(runtime, null)) {
                return false;
            }
            runtime.close();
            return true;
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
}
