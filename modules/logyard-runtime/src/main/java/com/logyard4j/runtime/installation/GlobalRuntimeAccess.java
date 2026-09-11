package com.logyard4j.runtime.installation;

import com.logyard4j.api.LogyardRuntime;

import java.util.function.BooleanSupplier;

public interface GlobalRuntimeAccess {
    LogyardRuntime current();

    /** Performs only the process-global slot installation; implementations must not invoke extension code or block. */
    void install(LogyardRuntime runtime);

    default void install(LogyardRuntime runtime, BooleanSupplier managedShutdown) {
        install(runtime);
    }

    default boolean detachIfCurrent(LogyardRuntime runtime) {
        return false;
    }

    boolean shutdownIfCurrent(LogyardRuntime runtime);
}
