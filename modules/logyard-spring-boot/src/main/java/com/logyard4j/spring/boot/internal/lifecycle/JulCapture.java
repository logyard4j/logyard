package com.logyard4j.spring.boot.internal.lifecycle;

import com.logyard4j.api.lifecycle.CloseLifecycle;
import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.jul.LogyardHandler;
import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/** Reference-counted JUL capture that restores the process logging state after the last Spring lease. */
final class JulCapture {
    private static final Object MONITOR = new Object();

    private static int leaseCount;
    private static LogyardRuntime capturedRuntime;
    private static LogyardHandler installedHandler;
    private static Handler[] displacedHandlers = {};
    private static Level displacedRootLevel;

    Lease acquire(LogyardRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        synchronized (MONITOR) {
            if (leaseCount == 0) {
                install(runtime);
            } else if (capturedRuntime != runtime) {
                throw new IllegalStateException("Spring JUL capture cannot bridge two Logyard runtime identities");
            }
            leaseCount++;
            return new Lease();
        }
    }

    private static void release() {
        LogyardHandler closing = null;
        synchronized (MONITOR) {
            leaseCount--;
            if (leaseCount == 0) {
                closing = restore();
            }
        }
        if (closing != null) {
            LogyardHandler handler = closing;
            SpringLifecycleBoundary.invoke("Spring JUL handler close", handler::close);
        }
    }

    private static void install(LogyardRuntime runtime) {
        Logger root = rootLogger();
        Handler[] currentHandlers = root.getHandlers();
        Level currentLevel = root.getLevel();
        LogyardHandler replacement = new LogyardHandler(runtime);
        try {
            Arrays.stream(currentHandlers).forEach(root::removeHandler);
            root.addHandler(replacement);
            root.setLevel(Level.ALL);
        } catch (Throwable failure) {
            Arrays.stream(root.getHandlers())
                    .filter(handler -> handler == replacement)
                    .forEach(root::removeHandler);
            Arrays.stream(currentHandlers).forEach(root::addHandler);
            root.setLevel(currentLevel);
            SpringLifecycleBoundary.invoke("failed Spring JUL handler close", replacement::close);
            throw failure;
        }
        capturedRuntime = runtime;
        installedHandler = replacement;
        displacedHandlers = currentHandlers;
        displacedRootLevel = currentLevel;
    }

    private static LogyardHandler restore() {
        Logger root = rootLogger();
        LogyardHandler handler = installedHandler;
        if (handler != null) {
            root.removeHandler(handler);
        }
        Arrays.stream(displacedHandlers).forEach(root::addHandler);
        root.setLevel(displacedRootLevel);
        capturedRuntime = null;
        installedHandler = null;
        displacedHandlers = new Handler[0];
        displacedRootLevel = null;
        return handler;
    }

    private static Logger rootLogger() {
        Logger root = LogManager.getLogManager().getLogger("");
        if (root == null) {
            throw new IllegalStateException("JUL root logger is unavailable");
        }
        return root;
    }

    /** Operation-owned claim on the process JUL bridge. */
    static final class Lease implements AutoCloseable {
        private final CloseLifecycle lifecycle = new CloseLifecycle();

        @Override
        public void close() {
            if (lifecycle.beginClose()) {
                release();
            }
        }
    }
}
