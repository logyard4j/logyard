package com.logyard4j.runtime.installation;

import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.api.reload.ReloadResult;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** Resource-owning runtime installation managed by the process lifecycle state machine. */
public interface RuntimeInstallation {
    LogyardRuntime runtime();

    /**
     * Applies the requested source, returning {@link ReloadResult#UNCHANGED} only when that exact
     * source identity and digest are already active.
     */
    ReloadResult reconfigure(ConfigurationInstallationRequest request);

    ReloadResult reloadNow();

    boolean watchesConfiguration();

    default Duration shutdownTimeout() {
        return Duration.ofSeconds(3L);
    }

    CompletionStage<Void> close(GlobalRuntimeAccess globalRuntime);
}
