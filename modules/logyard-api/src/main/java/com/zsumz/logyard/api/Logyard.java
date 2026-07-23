package com.zsumz.logyard.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Optional process-global access for application code and façade adapters. */
public final class Logyard {
    private static final AtomicReference<LogyardRuntime> RUNTIME = new AtomicReference<>();

    private Logyard() {
    }

    /**
     * Installs the process-global runtime.
     *
     * @param runtime runtime to install
     * @throws IllegalStateException if a runtime is already installed
     */
    public static void initialize(LogyardRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        if (!RUNTIME.compareAndSet(null, runtime)) {
            throw new IllegalStateException("Logyard is already initialized");
        }
    }

    /**
     * Returns whether a process-global runtime is installed.
     *
     * @return {@code true} when a runtime is installed
     */
    public static boolean isInitialized() {
        return RUNTIME.get() != null;
    }

    /**
     * Returns the process-global runtime, or {@code null} when none is installed.
     *
     * @return installed runtime, or {@code null}
     */
    public static LogyardRuntime runtimeOrNull() {
        return RUNTIME.get();
    }

    /**
     * Returns the process-global runtime.
     *
     * @return installed runtime
     * @throws IllegalStateException if no runtime is installed
     */
    public static LogyardRuntime runtime() {
        LogyardRuntime runtime = RUNTIME.get();
        if (runtime == null) {
            throw new IllegalStateException("Logyard is not initialized");
        }
        return runtime;
    }

    /**
     * Returns a logger named after a class.
     *
     * @param type class supplying the logger name
     * @return logger owned by the process-global runtime
     */
    public static LogyardLogger logger(Class<?> type) {
        return runtime().logger(type);
    }

    /**
     * Returns a logger with the supplied name.
     *
     * @param name logger name
     * @return logger owned by the process-global runtime
     */
    public static LogyardLogger logger(String name) {
        return runtime().logger(name);
    }

    /** Removes and closes the process-global runtime, if one is installed. */
    public static void shutdown() {
        LogyardRuntime runtime = RUNTIME.getAndSet(null);
        if (runtime != null) {
            runtime.close();
        }
    }
}
