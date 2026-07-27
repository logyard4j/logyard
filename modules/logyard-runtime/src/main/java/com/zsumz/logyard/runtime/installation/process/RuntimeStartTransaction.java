package com.zsumz.logyard.runtime.installation.process;

import com.zsumz.logyard.runtime.installation.RuntimeInstallation;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

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
    private final ShutdownBoundary shutdownBoundary = ShutdownBoundary.ownedByCurrentThread();
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

    synchronized boolean publish(Runnable publicationAction) {
        Objects.requireNonNull(publicationAction, "publicationAction");
        if (publication == Publication.CANCELLED) {
            return false;
        }
        if (publication != Publication.OPEN) {
            throw new IllegalStateException("runtime start publication was already attempted");
        }
        publication = Publication.PUBLISHING;
        try {
            // The action is the internal nonblocking global-slot CAS. Retaining this monitor makes
            // publication authority and terminal cancellation one indivisible transition.
            publicationAction.run();
        } catch (RuntimeException | Error failure) {
            publication = Publication.CANCELLED;
            throw failure;
        }
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

    void completeWithoutRetirement() {
        shutdownBoundary.complete();
        finalRetirement.complete(null);
    }

    void completeShutdownBoundary() {
        shutdownBoundary.complete();
    }

    void completeFinalRetirement() {
        finalRetirement.complete(null);
    }

    boolean awaitShutdownBoundary() {
        Duration timeout = candidate == null ? ShutdownBoundary.DEFAULT_TIMEOUT : candidate.shutdownTimeout();
        return shutdownBoundary.await(timeout);
    }

    boolean finalRetirementComplete() {
        return finalRetirement.isDone();
    }
}
