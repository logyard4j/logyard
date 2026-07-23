package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.api.LogyardRuntime;

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
    public boolean shutdownIfCurrent(LogyardRuntime runtime) {
        return Logyard.shutdownIfCurrent(runtime);
    }
}
