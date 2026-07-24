package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.reload.ReloadResult;

import java.util.concurrent.CompletionStage;

/** Resource-owning runtime installation managed by the process lifecycle state machine. */
interface RuntimeInstallation {
    LogyardRuntime runtime();

    ReloadResult reconfigure(ConfigurationInstallationRequest request);

    ReloadResult reloadNow();

    boolean watchesConfiguration();

    CompletionStage<Void> close(GlobalRuntimeAccess globalRuntime);
}
