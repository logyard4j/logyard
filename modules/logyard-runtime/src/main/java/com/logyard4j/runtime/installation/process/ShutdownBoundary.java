package com.logyard4j.runtime.installation.process;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One bounded waiting boundary between the thread that begins shutdown and asynchronous retirement. */
final class ShutdownBoundary {
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3L);

    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private Thread owner;
    private Duration configuredTimeout = DEFAULT_TIMEOUT;

    static ShutdownBoundary ownedByCurrentThread() {
        ShutdownBoundary boundary = new ShutdownBoundary();
        boundary.owner = Thread.currentThread();
        return boundary;
    }

    synchronized boolean claim(Duration timeout) {
        if (owner != null) {
            return false;
        }
        owner = Thread.currentThread();
        configuredTimeout = normalize(timeout);
        return true;
    }

    void complete() {
        completion.complete(null);
    }

    boolean awaitConfiguredTimeout() {
        Duration timeout;
        synchronized (this) {
            timeout = configuredTimeout;
        }
        return await(timeout);
    }

    boolean await(Duration timeout) {
        Thread closingThread;
        synchronized (this) {
            closingThread = owner;
        }
        if (Thread.currentThread() == closingThread) {
            return completion.isDone();
        }
        try {
            completion.get(saturatedNanos(Objects.requireNonNull(timeout, "timeout")), TimeUnit.NANOSECONDS);
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

    void onCompletion(Runnable observer) {
        completion.whenComplete((ignored, failure) -> observer.run());
    }

    private static Duration normalize(Duration timeout) {
        return timeout == null || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return Math.max(1L, duration.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
