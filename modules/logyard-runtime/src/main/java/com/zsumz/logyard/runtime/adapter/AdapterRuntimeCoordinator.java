package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import com.zsumz.logyard.runtime.diagnostics.AdapterDiagnostics;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class AdapterRuntimeCoordinator {
    private static final long DEFAULT_TRANSITION_WAIT_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final Object monitor = new Object();
    private final Supplier<RuntimeBundle> bootstrap;
    private final Supplier<LogyardRuntime> currentRuntime;
    private final ShutdownHookRegistrar shutdownHooks;
    private final AdapterRuntimeCloser runtimeCloser = new AdapterRuntimeCloser();
    private final long transitionWaitNanos;

    private SharedAdapterRuntime owned;
    private Thread transitionThread;

    AdapterRuntimeCoordinator() {
        this(LogyardBootstrap::start, Logyard::runtimeOrNull, new AdapterShutdownHook(), DEFAULT_TRANSITION_WAIT_NANOS);
    }

    AdapterRuntimeCoordinator(
            Supplier<RuntimeBundle> bootstrap,
            Supplier<LogyardRuntime> currentRuntime,
            ShutdownHookRegistrar shutdownHooks,
            long transitionWaitNanos) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.currentRuntime = Objects.requireNonNull(currentRuntime, "currentRuntime");
        this.shutdownHooks = Objects.requireNonNull(shutdownHooks, "shutdownHooks");
        if (transitionWaitNanos <= 0L) {
            throw new IllegalArgumentException("transitionWaitNanos must be positive");
        }
        this.transitionWaitNanos = transitionWaitNanos;
    }

    AdapterRuntimeHandle resolve(String adapterName) {
        while (true) {
            RuntimeBundle stale = null;
            synchronized (monitor) {
                awaitTransition();
                if (owned != null) {
                    if (owned.globallyActive()) {
                        return owned.lease();
                    }
                    beginTransition();
                    stale = owned.forceClose();
                    owned = null;
                } else {
                    LogyardRuntime existing = currentRuntime.get();
                    if (existing != null) {
                        return new AdapterRuntimeHandle(new BorrowedRuntimeLease(existing, currentRuntime));
                    }
                    beginTransition();
                }
            }

            if (stale != null) {
                closeStale(stale);
                continue;
            }
            return initializeOwned(adapterName);
        }
    }

    boolean isCurrent(LogyardRuntime runtime) {
        return currentRuntime.get() == runtime;
    }

    void release(SharedAdapterRuntime shared) {
        RuntimeBundle closing;
        synchronized (monitor) {
            if (owned != shared || !shared.releaseLease()) {
                return;
            }
            beginTransition();
            owned = null;
            closing = shared.forceClose();
        }
        try {
            runtimeCloser.closeReleased(closing);
        } finally {
            finishTransition();
        }
    }

    private AdapterRuntimeHandle initializeOwned(String adapterName) {
        RuntimeBundle bundle = null;
        Throwable failure = null;
        try {
            bundle = bootstrap.get();
        } catch (Throwable bootstrapFailure) {
            failure = bootstrapFailure;
        }

        AdapterRuntimeHandle result = null;
        synchronized (monitor) {
            try {
                if (bundle != null) {
                    owned = new SharedAdapterRuntime(this, bundle);
                    shutdownHooks.install(adapterName, this::closeOwned);
                    result = owned.lease();
                } else {
                    LogyardRuntime winner = currentRuntime.get();
                    if (winner != null) {
                        result = new AdapterRuntimeHandle(new BorrowedRuntimeLease(winner, currentRuntime));
                    }
                }
            } finally {
                clearTransition();
            }
        }
        if (result != null) {
            return result;
        }
        AdapterDiagnostics.rethrowIfFatal(failure);
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw new IllegalStateException("failed to initialize Logyard adapter runtime", failure);
    }

    private void closeStale(RuntimeBundle stale) {
        try {
            runtimeCloser.closeStale(stale);
        } finally {
            finishTransition();
        }
    }

    private void closeOwned() {
        RuntimeBundle closing;
        synchronized (monitor) {
            if (transitionThread != null || owned == null) {
                return;
            }
            beginTransition();
            closing = owned.forceClose();
            owned = null;
        }

        try {
            runtimeCloser.closeAtShutdown(closing);
        } finally {
            finishTransition();
        }
    }

    private void awaitTransition() {
        if (transitionThread == Thread.currentThread()) {
            throw new IllegalStateException("recursive Logyard adapter runtime transition");
        }
        long deadline = saturatedAdd(System.nanoTime(), transitionWaitNanos);
        while (transitionThread != null) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                throw new IllegalStateException("timed out awaiting Logyard adapter runtime transition");
            }
            try {
                monitor.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting Logyard adapter runtime", interrupted);
            }
        }
    }

    private void beginTransition() {
        if (transitionThread != null) {
            throw new IllegalStateException("Logyard adapter runtime transition is already active");
        }
        transitionThread = Thread.currentThread();
    }

    private void finishTransition() {
        synchronized (monitor) {
            clearTransition();
        }
    }

    private void clearTransition() {
        if (transitionThread != Thread.currentThread()) {
            throw new IllegalStateException("Logyard adapter runtime transition ownership was lost");
        }
        transitionThread = null;
        monitor.notifyAll();
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        return result < left ? Long.MAX_VALUE : result;
    }
}
