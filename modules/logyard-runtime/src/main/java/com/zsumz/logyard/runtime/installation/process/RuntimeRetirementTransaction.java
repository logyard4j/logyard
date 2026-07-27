package com.zsumz.logyard.runtime.installation.process;

import com.zsumz.logyard.runtime.installation.RuntimeInstallation;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** One shared close operation with distinct bounded-shutdown and final-retirement stages. */
final class RuntimeRetirementTransaction {
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(3L);

    private final RuntimeInstallation installation;
    private final long generation;
    private final CompletableFuture<Void> shutdownBoundary = new CompletableFuture<>();
    private final CompletableFuture<Void> finalRetirement = new CompletableFuture<>();
    private Thread owner;
    private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;

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
        if (owner != null) {
            return false;
        }
        Duration configured = installation.shutdownTimeout();
        shutdownTimeout = configured == null || configured.isNegative() ? DEFAULT_SHUTDOWN_TIMEOUT : configured;
        owner = Thread.currentThread();
        return true;
    }

    boolean awaitShutdownBoundary() {
        Thread closingThread;
        Duration timeout;
        synchronized (this) {
            closingThread = owner;
            timeout = shutdownTimeout;
        }
        if (Thread.currentThread() == closingThread) {
            return shutdownBoundary.isDone();
        }
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

    void completeShutdownBoundary() {
        shutdownBoundary.complete(null);
    }

    void completeFinalRetirement(Throwable failure) {
        if (failure == null) {
            finalRetirement.complete(null);
        } else {
            finalRetirement.completeExceptionally(failure);
        }
    }

    void onShutdownBoundary(Runnable observer) {
        shutdownBoundary.whenComplete((ignored, failure) -> observer.run());
    }

    void onFinalRetirement(Consumer<Throwable> observer) {
        finalRetirement.whenComplete((ignored, failure) -> observer.accept(failure));
    }

    CompletionStage<Void> finalRetirement() {
        return finalRetirement;
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return Math.max(1L, duration.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
