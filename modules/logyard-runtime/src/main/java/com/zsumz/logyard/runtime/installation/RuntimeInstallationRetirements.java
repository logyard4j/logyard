package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.runtime.diagnostics.AdapterDiagnostics;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Executes installation retirement and diagnostics outside the manager state monitor. */
final class RuntimeInstallationRetirements {
    CompletionStage<Void> close(
            RuntimeInstallation installation,
            GlobalRuntimeAccess globalRuntime,
            Consumer<Throwable> completionObserver) {
        CompletionStage<Void> completion;
        try {
            completion = installation.close(globalRuntime);
        } catch (Throwable failure) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            completion = CompletableFuture.failedFuture(failure);
        }
        completion.whenComplete((ignored, failure) -> {
            completionObserver.accept(failure);
            if (failure != null) {
                AdapterDiagnostics.adapterFailure("runtime", "shutdown", failure);
            }
        });
        return completion;
    }
}
