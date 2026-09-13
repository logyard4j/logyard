package com.logyard4j.logyard.runtime.installation;

import com.logyard4j.logyard.core.failure.ComponentFailureCollector;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Combines synchronous close-boundary failures with asynchronous runtime retirement. */
final class RuntimeInstallationClosure {
    private RuntimeInstallationClosure() {
    }

    static CompletionStage<Void> withFailures(CompletionStage<Void> retirement, ComponentFailureCollector failures) {
        try {
            failures.throwIfPresent("runtime installation close");
            return retirement;
        } catch (RuntimeException failure) {
            return retirement.handle((ignored, retirementFailure) -> {
                if (retirementFailure != null) {
                    failure.addSuppressed(retirementFailure);
                }
                throw new CompletionException(failure);
            });
        }
    }
}
