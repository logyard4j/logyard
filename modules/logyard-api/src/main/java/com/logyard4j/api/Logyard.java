package com.logyard4j.api;

import com.logyard4j.api.annotation.InternalApi;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Optional process-global access for application code and façade adapters. */
public final class Logyard {
    private static final AtomicReference<RuntimeSlot> RUNTIME = new AtomicReference<>();

    private Logyard() {
    }

    /**
     * Installs the process-global runtime.
     *
     * @param runtime runtime to install
     * @throws IllegalStateException if a runtime is already installed
     */
    public static void initialize(LogyardRuntime runtime) {
        install(new RuntimeSlot(Objects.requireNonNull(runtime, "runtime"), null));
    }

    /**
     * Installs a process-global runtime whose shutdown is coordinated by its lifecycle owner.
     *
     * @param runtime runtime to install
     * @param managedShutdown callback that atomically retires the owning lifecycle
     * @hidden
     */
    @InternalApi
    public static void initializeManaged(LogyardRuntime runtime, BooleanSupplier managedShutdown) {
        install(new RuntimeSlot(
                Objects.requireNonNull(runtime, "runtime"),
                Objects.requireNonNull(managedShutdown, "managedShutdown")));
    }

    private static void install(RuntimeSlot slot) {
        if (!RUNTIME.compareAndSet(null, slot)) {
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
        RuntimeSlot slot = RUNTIME.get();
        return slot == null ? null : slot.runtime();
    }

    /**
     * Returns the process-global runtime.
     *
     * @return installed runtime
     * @throws IllegalStateException if no runtime is installed
     */
    public static LogyardRuntime runtime() {
        RuntimeSlot slot = RUNTIME.get();
        if (slot == null) {
            throw new IllegalStateException("Logyard is not initialized");
        }
        return slot.runtime();
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
        while (true) {
            RuntimeSlot slot = RUNTIME.get();
            if (slot == null || shutdown(slot)) {
                return;
            }
        }
    }

    /**
     * Removes and closes the expected process-global runtime without affecting a replacement.
     *
     * @param expected runtime expected to be installed
     * @return {@code true} when the expected runtime was removed and closed
     */
    public static boolean shutdownIfCurrent(LogyardRuntime expected) {
        Objects.requireNonNull(expected, "expected");
        RuntimeSlot slot = RUNTIME.get();
        if (slot == null || slot.runtime() != expected) {
            return false;
        }
        return shutdown(slot);
    }

    /**
     * Removes and closes an expected manager-owned runtime after the manager has retired its leases.
     *
     * @param expected managed runtime expected to occupy the global slot
     * @return {@code true} when the expected runtime was removed and closed
     * @hidden
     */
    @InternalApi
    public static boolean releaseManagedIfCurrent(LogyardRuntime expected) {
        Objects.requireNonNull(expected, "expected");
        RuntimeSlot slot = RUNTIME.get();
        if (slot == null || slot.runtime() != expected || slot.managedShutdown() == null
                || !RUNTIME.compareAndSet(slot, null)) {
            return false;
        }
        expected.close();
        return true;
    }

    /**
     * Removes an expected manager-owned runtime from the global slot without closing it.
     *
     * <p>The lifecycle manager uses this when a retirement transaction retains sole responsibility
     * for closing the runtime but a cancelled start, reentrant close, or bounded shutdown wait must
     * make the process-global slot unavailable immediately.</p>
     *
     * @param expected managed runtime expected to occupy the global slot
     * @return {@code true} when the expected runtime was detached
     * @hidden
     */
    @InternalApi
    public static boolean detachManagedIfCurrent(LogyardRuntime expected) {
        Objects.requireNonNull(expected, "expected");
        RuntimeSlot slot = RUNTIME.get();
        return slot != null
                && slot.runtime() == expected
                && slot.managedShutdown() != null
                && RUNTIME.compareAndSet(slot, null);
    }

    private static boolean shutdown(RuntimeSlot slot) {
        if (slot.managedShutdown() != null) {
            return slot.managedShutdown().getAsBoolean();
        }
        if (!RUNTIME.compareAndSet(slot, null)) {
            return false;
        }
        slot.runtime().close();
        return true;
    }

    private record RuntimeSlot(LogyardRuntime runtime, BooleanSupplier managedShutdown) {
    }
}
