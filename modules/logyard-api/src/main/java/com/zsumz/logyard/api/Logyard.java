package com.zsumz.logyard.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Optional process-global access for application code and façade adapters. */
public final class Logyard {
    private static final AtomicReference<LogyardRuntime> RUNTIME = new AtomicReference<>();

    private Logyard() {
    }

    public static void initialize(LogyardRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        if (!RUNTIME.compareAndSet(null, runtime)) {
            throw new IllegalStateException("Logyard is already initialized");
        }
    }

    public static boolean isInitialized() {
        return RUNTIME.get() != null;
    }

    public static LogyardRuntime runtimeOrNull() {
        return RUNTIME.get();
    }

    public static LogyardRuntime runtime() {
        LogyardRuntime runtime = RUNTIME.get();
        if (runtime == null) {
            throw new IllegalStateException("Logyard is not initialized");
        }
        return runtime;
    }

    public static LogyardLogger logger(Class<?> type) { return runtime().logger(type); }
    public static LogyardLogger logger(String name) { return runtime().logger(name); }

    public static void shutdown() {
        LogyardRuntime runtime = RUNTIME.getAndSet(null);
        if (runtime != null) {
            runtime.close();
        }
    }
}
