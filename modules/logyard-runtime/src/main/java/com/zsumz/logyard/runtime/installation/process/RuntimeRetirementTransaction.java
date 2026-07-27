package com.zsumz.logyard.runtime.installation.process;

import com.zsumz.logyard.runtime.installation.RuntimeInstallation;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** One shared close operation with distinct bounded-shutdown and final-retirement stages. */
final class RuntimeRetirementTransaction {
    private final RuntimeInstallation installation;
    private final long generation;
    private final ShutdownBoundary shutdownBoundary = new ShutdownBoundary();
    private final CompletableFuture<Void> finalRetirement = new CompletableFuture<>();

    RuntimeRetirementTransaction(RuntimeInstallation installation, long generation) {
        this.installation = Objects.requireNonNull(installation, "installation");
        this.generation = generation;
    }

    RuntimeInstallation installation() {
        return installation;
    }

    long generation() {
        return generation;
    }

    synchronized boolean claim() {
        return shutdownBoundary.claim(installation.shutdownTimeout());
    }

    boolean awaitShutdownBoundary() {
        return shutdownBoundary.awaitConfiguredTimeout();
    }

    void completeShutdownBoundary() {
        shutdownBoundary.complete();
    }

    void completeFinalRetirement(Throwable failure) {
        if (failure == null) {
            finalRetirement.complete(null);
        } else {
            finalRetirement.completeExceptionally(failure);
        }
    }

    void onShutdownBoundary(Runnable observer) {
        shutdownBoundary.onCompletion(observer);
    }

    void onFinalRetirement(Consumer<Throwable> observer) {
        finalRetirement.whenComplete((ignored, failure) -> observer.accept(failure));
    }

    CompletionStage<Void> finalRetirement() {
        return finalRetirement;
    }
}
