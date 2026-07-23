package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.LogyardRuntime;

interface GlobalRuntimeAccess {
    LogyardRuntime current();

    void install(LogyardRuntime runtime);

    boolean shutdownIfCurrent(LogyardRuntime runtime);
}
