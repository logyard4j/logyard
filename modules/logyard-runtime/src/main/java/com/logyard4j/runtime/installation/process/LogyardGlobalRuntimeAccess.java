package com.logyard4j.runtime.installation.process;

import com.logyard4j.api.Logyard;
import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.runtime.installation.GlobalRuntimeAccess;

import java.util.function.BooleanSupplier;

final class LogyardGlobalRuntimeAccess implements GlobalRuntimeAccess {
    @Override
    public LogyardRuntime current() {
        return Logyard.runtimeOrNull();
    }

    @Override
    public void install(LogyardRuntime runtime) {
        Logyard.initialize(runtime);
    }

    @Override
    public void install(LogyardRuntime runtime, BooleanSupplier managedShutdown) {
        if (managedShutdown == null) {
            Logyard.initialize(runtime);
        } else {
            Logyard.initializeManaged(runtime, managedShutdown);
        }
    }

    @Override
    public boolean detachIfCurrent(LogyardRuntime runtime) {
        return Logyard.detachManagedIfCurrent(runtime);
    }

    @Override
    public boolean shutdownIfCurrent(LogyardRuntime runtime) {
        return Logyard.releaseManagedIfCurrent(runtime);
    }
}
