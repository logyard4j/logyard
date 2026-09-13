package com.logyard4j.logyard.runtime.installation.process;

import com.logyard4j.logyard.api.Logyard;
import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.runtime.installation.GlobalRuntimeAccess;

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
