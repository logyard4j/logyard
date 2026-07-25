package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.api.LogyardRuntime;

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
