package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.LogyardRuntime;

import java.util.function.BooleanSupplier;

interface GlobalRuntimeAccess {
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
