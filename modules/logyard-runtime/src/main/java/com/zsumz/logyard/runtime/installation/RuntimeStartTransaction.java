package com.zsumz.logyard.runtime.installation;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Single-owner token separating a cancelled start's bounded shutdown boundary from final asynchronous retirement. */
final class RuntimeStartTransaction {
    private enum Publication {
        OPEN,
        PUBLISHING,
        PUBLISHED,
        CANCELLATION_REQUESTED,
        CANCELLED
    }

    private final long generation;
    private final Thread owner = Thread.currentThread();
    private final CompletableFuture<Void> shutdownBoundary = new CompletableFuture<>();
    private final CompletableFuture<Void> finalRetirement = new CompletableFuture<>();
    private RuntimeInstallation candidate;
    private Publication publication = Publication.OPEN;

    RuntimeStartTransaction(long generation) {
        this.generation = generation;
    }

    long generation() {
        return generation;
    }

    synchronized RuntimeInstallation candidate() {
        return candidate;
    }

    synchronized void candidate(RuntimeInstallation candidate) {
        this.candidate = candidate;
    }

    synchronized boolean cancelled() {
        return publication == Publication.CANCELLATION_REQUESTED || publication == Publication.CANCELLED;
    }

    synchronized void cancel() {
        publication = switch (publication) {
            case OPEN, PUBLISHED -> Publication.CANCELLED;
            case PUBLISHING -> Publication.CANCELLATION_REQUESTED;
            case CANCELLATION_REQUESTED, CANCELLED -> publication;
        };
    }

    boolean publish(Runnable publicationAction) {
        Objects.requireNonNull(publicationAction, "publicationAction");
        synchronized (this) {
            if (publication == Publication.CANCELLED) {
                return false;
            }
            if (publication != Publication.OPEN) {
                throw new IllegalStateException("runtime start publication was already attempted");
            }
            publication = Publication.PUBLISHING;
        }
        try {
            publicationAction.run();
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                publication = Publication.CANCELLED;
            }
            throw failure;
        }
        synchronized (this) {
            if (publication == Publication.CANCELLATION_REQUESTED) {
                publication = Publication.CANCELLED;
                return false;
            }
            if (publication != Publication.PUBLISHING) {
                throw new IllegalStateException("runtime start publication was not in progress");
            }
            publication = Publication.PUBLISHED;
            return true;
        }
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
