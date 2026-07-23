package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;
import com.zsumz.logyard.runtime.diagnostics.AdapterDiagnostics;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide runtime resolver shared by every logging façade adapter. */
public final class AdapterRuntimeResolver {
    private static final Object MONITOR = new Object();
    private static final long TRANSITION_WAIT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final AtomicBoolean HOOK_INSTALLED = new AtomicBoolean();

    private static SharedOwned owned;
    private static Thread transitionThread;

    private AdapterRuntimeResolver() {
    }

    /** Borrows an application runtime, or leases exactly one adapter-owned runtime. */
    public static AdapterRuntimeHandle resolve(String adapterName) {
        String normalizedName = requireName(adapterName);
        while (true) {
            RuntimeBundle stale = null;
            synchronized (MONITOR) {
                awaitTransition();
                if (owned != null) {
                    if (owned.globallyActive()) {
                        return owned.lease();
                    }
                    beginTransition();
                    stale = owned.forceClose();
                    owned = null;
                } else {
                    LogyardRuntime existing = Logyard.runtimeOrNull();
                    if (existing != null) {
                        return new AdapterRuntimeHandle(existing, null);
                    }
                    beginTransition();
                }
            }

            if (stale != null) {
                closeStale(stale);
                continue;
            }
            return initializeOwned(normalizedName);
        }
    }

    static List<String> contextInclude(LogyardRuntime runtime) {
        return LogyardRuntimeFactory.contextIncludeFor(runtime);
    }

    static void release(SharedOwned shared) {
        RuntimeBundle closing;
        synchronized (MONITOR) {
            if (owned != shared || !shared.release()) {
                return;
            }
            beginTransition();
            owned = null;
            closing = shared.bundle;
        }

        Throwable failure = null;
        try {
            closing.close();
        } catch (Throwable closeFailure) {
            failure = closeFailure;
        } finally {
            finishTransition();
        }
        if (failure != null) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            throw new IllegalStateException("failed to close shared Logyard adapter runtime", failure);
        }
    }

    private static AdapterRuntimeHandle initializeOwned(String adapterName) {
        RuntimeBundle bundle = null;
        Throwable failure = null;
        try {
            bundle = LogyardBootstrap.start();
        } catch (Throwable bootstrapFailure) {
            failure = bootstrapFailure;
        }

        AdapterRuntimeHandle result = null;
        synchronized (MONITOR) {
            try {
                if (bundle != null) {
                    owned = new SharedOwned(bundle);
                    installShutdownHook(adapterName);
                    result = owned.lease();
                } else {
                    LogyardRuntime winner = Logyard.runtimeOrNull();
                    if (winner != null) {
                        result = new AdapterRuntimeHandle(winner, null);
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

    private static void closeStale(RuntimeBundle stale) {
        Throwable failure = null;
        try {
            stale.close();
        } catch (Throwable closeFailure) {
            failure = closeFailure;
        } finally {
            finishTransition();
        }
        if (failure != null) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            AdapterDiagnostics.adapterFailure("shared", "stale runtime cleanup", failure);
        }
    }

    private static void closeOwned() {
        RuntimeBundle closing = null;
        synchronized (MONITOR) {
            if (transitionThread != null || owned == null) {
                return;
            }
            beginTransition();
            closing = owned.forceClose();
            owned = null;
        }

        Throwable failure = null;
        try {
            closing.close();
        } catch (Throwable closeFailure) {
            failure = closeFailure;
        } finally {
            finishTransition();
        }
        if (failure != null) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            AdapterDiagnostics.adapterFailure("shared", "shutdown", failure);
        }
    }

    private static void installShutdownHook(String adapterName) {
        if (!HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(
                    AdapterRuntimeResolver::closeOwned,
                    "logyard-" + threadComponent(adapterName) + "-shutdown"));
        } catch (IllegalStateException | SecurityException failure) {
            HOOK_INSTALLED.set(false);
            AdapterDiagnostics.shutdownHookFailure(failure);
        }
    }

    private static void awaitTransition() {
        if (transitionThread == Thread.currentThread()) {
            throw new IllegalStateException("recursive Logyard adapter runtime transition");
        }
        long deadline = saturatedAdd(System.nanoTime(), TRANSITION_WAIT_NANOS);
        while (transitionThread != null) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                throw new IllegalStateException("timed out awaiting Logyard adapter runtime transition");
            }
            long millis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
            try {
                MONITOR.wait(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while awaiting Logyard adapter runtime", interrupted);
            }
        }
    }

    private static void beginTransition() {
        if (transitionThread != null) {
            throw new IllegalStateException("Logyard adapter runtime transition is already active");
        }
        transitionThread = Thread.currentThread();
    }

    private static void finishTransition() {
        synchronized (MONITOR) {
            clearTransition();
        }
    }

    private static void clearTransition() {
        if (transitionThread != Thread.currentThread()) {
            throw new IllegalStateException("Logyard adapter runtime transition ownership was lost");
        }
        transitionThread = null;
        MONITOR.notifyAll();
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        return result < left ? Long.MAX_VALUE : result;
    }

    private static String requireName(String value) {
        String normalized = Objects.requireNonNull(value, "adapterName").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("adapterName must not be blank");
        }
        return normalized;
    }

    private static String threadComponent(String value) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), 48));
        for (int index = 0; index < value.length() && result.length() < 48; index++) {
            char character = Character.toLowerCase(value.charAt(index));
            result.append(Character.isLetterOrDigit(character) ? character : '-');
        }
        return result.toString();
    }

    static final class SharedOwned {
        private final RuntimeBundle bundle;
        private int leases;
        private volatile boolean closed;

        private SharedOwned(RuntimeBundle bundle) {
            this.bundle = Objects.requireNonNull(bundle, "bundle");
        }

        private AdapterRuntimeHandle lease() {
            if (closed) {
                throw new IllegalStateException("Logyard adapter runtime is closing");
            }
            leases++;
            return new AdapterRuntimeHandle(bundle.runtime(), this);
        }

        boolean active(LogyardRuntime runtime) {
            return !closed && bundle.runtime() == runtime && Logyard.runtimeOrNull() == runtime;
        }

        boolean globallyActive() {
            return !closed && Logyard.runtimeOrNull() == bundle.runtime();
        }

        List<String> contextInclude() {
            return closed ? List.of() : bundle.contextInclude();
        }

        private boolean release() {
            if (closed || leases == 0) {
                return false;
            }
            leases--;
            if (leases == 0) {
                closed = true;
                return true;
            }
            return false;
        }

        private RuntimeBundle forceClose() {
            closed = true;
            leases = 0;
            return bundle;
        }
    }
}
