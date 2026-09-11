package com.logyard4j.runtime.installation.process;

import com.logyard4j.runtime.installation.ConfigurationInstallationRequest;
import com.logyard4j.runtime.installation.GlobalRuntimeAccess;
import com.logyard4j.runtime.installation.RuntimeInstallation;

import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.api.reload.ReloadResult;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimePrePublicationShutdownTest {
    @Test
    void terminalShutdownRevokesAPendingStartsPublicationAuthorityBeforeReturning() throws Exception {
        CountingGlobal global = new CountingGlobal();
        AtomicReference<Runnable> shutdownHook = new AtomicReference<>();
        CountDownLatch publicationReached = new CountDownLatch(1);
        CountDownLatch allowPublication = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();
        AtomicReference<PrePublicationInstallation> pending = new AtomicReference<>();
        RuntimeInstallationManager manager = new RuntimeInstallationManager(
                global,
                callback -> {
                    shutdownHook.set(callback);
                    return true;
                },
                Map::of,
                (request, environment) -> {
                    if (opens.getAndIncrement() == 0) {
                        return new ImmediateInstallation();
                    }
                    PrePublicationInstallation installation =
                            new PrePublicationInstallation(publicationReached, allowPublication);
                    pending.set(installation);
                    return installation;
                });
        assertTimeoutPreemptively(Duration.ofSeconds(2L), () -> manager.acquireApplication(TestRequests.request()).close());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RuntimeInstallationLease> acquisition =
                    executor.submit(() -> manager.acquireApplication(TestRequests.request()));
            assertTrue(publicationReached.await(1L, TimeUnit.SECONDS));

            assertTimeoutPreemptively(Duration.ofSeconds(2L), shutdownHook.get()::run);

            assertNull(global.current());
            assertEquals(1, global.installCalls.get());

            allowPublication.countDown();
            ExecutionException cancellation =
                    assertThrows(ExecutionException.class, () -> acquisition.get(2L, TimeUnit.SECONDS));
            assertTrue(cancellation.getCause() instanceof RuntimeTransitionInProgressException);
            assertEquals(1, global.installCalls.get(), "cancelled start published after terminal shutdown returned");
            assertEquals(1, pending.get().closeCalls.get());
            assertNull(global.current());
        } finally {
            allowPublication.countDown();
            executor.shutdownNow();
        }
    }

    private static final class CountingGlobal implements GlobalRuntimeAccess {
        private final AtomicReference<LogyardRuntime> current = new AtomicReference<>();
        private final AtomicInteger installCalls = new AtomicInteger();

        @Override public LogyardRuntime current() { return current.get(); }

        @Override
        public void install(LogyardRuntime runtime) {
            installCalls.incrementAndGet();
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

    private static class ImmediateInstallation implements RuntimeInstallation {
        protected final LogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(ignored -> {
        });

        @Override public LogyardRuntime runtime() { return runtime; }
        @Override public ReloadResult reconfigure(ConfigurationInstallationRequest request) { return ReloadResult.UNCHANGED; }
        @Override public ReloadResult reloadNow() { return ReloadResult.UNCHANGED; }
        @Override public boolean watchesConfiguration() { return false; }

        @Override
        public java.util.concurrent.CompletionStage<Void> close(GlobalRuntimeAccess globalRuntime) {
            if (!globalRuntime.shutdownIfCurrent(runtime)) {
                runtime.close();
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class PrePublicationInstallation extends ImmediateInstallation {
        private final CountDownLatch publicationReached;
        private final CountDownLatch allowPublication;
        private final AtomicBoolean firstRuntimeRead = new AtomicBoolean(true);
        private final AtomicInteger closeCalls = new AtomicInteger();

        private PrePublicationInstallation(CountDownLatch publicationReached, CountDownLatch allowPublication) {
            this.publicationReached = publicationReached;
            this.allowPublication = allowPublication;
        }

        @Override
        public LogyardRuntime runtime() {
            if (firstRuntimeRead.compareAndSet(true, false)) {
                publicationReached.countDown();
                await(allowPublication);
            }
            return runtime;
        }

        @Override public Duration shutdownTimeout() { return Duration.ofMillis(20L); }

        @Override
        public java.util.concurrent.CompletionStage<Void> close(GlobalRuntimeAccess globalRuntime) {
            closeCalls.incrementAndGet();
            return super.close(globalRuntime);
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
