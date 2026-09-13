package com.logyard4j.logyard.runtime.installation.process;

import com.logyard4j.logyard.runtime.diagnostics.AdapterDiagnostics;
import com.logyard4j.logyard.runtime.installation.GlobalRuntimeAccess;
import com.logyard4j.logyard.runtime.installation.RuntimeInstallation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Executes installation retirement and diagnostics outside the manager state monitor. */
final class RuntimeInstallationRetirements {
    CompletionStage<Void> close(RuntimeInstallation installation, GlobalRuntimeAccess globalRuntime) {
        try {
            return installation.close(globalRuntime);
        } catch (Throwable failure) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            return CompletableFuture.failedFuture(failure);
        }
    }

    void observe(CompletionStage<Void> completion, Consumer<Throwable> completionObserver) {
        completion.whenComplete((ignored, failure) -> {
            completionObserver.accept(failure);
            if (failure != null) {
                AdapterDiagnostics.adapterFailure("runtime", "shutdown", failure);
            }
        });
    }
}
