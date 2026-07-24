package com.zsumz.logyard.runtime.installation;

import java.util.concurrent.CompletableFuture;

/** Single-owner token for one runtime construction and publication attempt. */
final class RuntimeStartTransaction {
    private final long generation;
    private final Thread owner = Thread.currentThread();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private RuntimeInstallation candidate;
    private boolean cancelled;

    RuntimeStartTransaction(long generation) {
        this.generation = generation;
    }

    long generation() {
        return generation;
    }

    RuntimeInstallation candidate() {
        return candidate;
    }

    void candidate(RuntimeInstallation candidate) {
        this.candidate = candidate;
    }

    boolean cancelled() {
        return cancelled;
    }

    void cancel() {
        cancelled = true;
    }

    void complete() {
        completion.complete(null);
    }

    void awaitCompletion() {
        if (Thread.currentThread() != owner) {
            completion.join();
        }
    }
}
