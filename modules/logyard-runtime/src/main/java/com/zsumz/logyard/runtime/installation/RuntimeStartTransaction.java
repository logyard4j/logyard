package com.zsumz.logyard.runtime.installation;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Single-owner token separating a cancelled start's bounded shutdown boundary from final asynchronous retirement. */
final class RuntimeStartTransaction {
    private final long generation;
    private final Thread owner = Thread.currentThread();
    private final CompletableFuture<Void> shutdownBoundary = new CompletableFuture<>();
    private final CompletableFuture<Void> finalRetirement = new CompletableFuture<>();
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

    void completeWithoutRetirement() {
        shutdownBoundary.complete(null);
        finalRetirement.complete(null);
    }

    void completeShutdownBoundary() {
        shutdownBoundary.complete(null);
    }

    void completeFinalRetirement() {
        finalRetirement.complete(null);
    }

    boolean awaitShutdownBoundary() {
        if (Thread.currentThread() == owner) {
            return shutdownBoundary.isDone();
        }
        Duration timeout = candidate == null ? Duration.ofSeconds(3L) : candidate.shutdownTimeout();
        try {
            shutdownBoundary.get(saturatedNanos(timeout), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException impossible) {
            return true;
        } catch (TimeoutException timeoutElapsed) {
            return false;
        }
    }

    boolean finalRetirementComplete() {
        return finalRetirement.isDone();
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return Math.max(1L, duration.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
