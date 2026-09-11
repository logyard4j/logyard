package com.logyard4j.runtime.adapter;

import com.logyard4j.api.annotation.InternalApi;

import java.time.Duration;
import java.util.Objects;

/**
 * Allocation-free admission and drain barrier for framework logging handlers.
 *
 * <p>Retirement atomically rejects new publications, then waits within a caller-supplied bound for
 * already admitted publications. An interrupted retiring thread preserves its interrupt status.</p>
 */
@InternalApi
public final class PublicationGate {
    private final PublicationAdmissionState admission = new PublicationAdmissionState();
    private final Object drained = new Object();

    /**
     * Attempts to admit one publication.
     *
     * @return {@code true} when admitted, or {@code false} after retirement begins
     */
    public boolean tryEnter() {
        return admission.tryEnter();
    }

    /** Releases one previously admitted publication. */
    public void exit() {
        if (admission.exitAndRetiredGateIsDrained()) {
            synchronized (drained) {
                drained.notifyAll();
            }
        }
    }

    /**
     * Rejects new publications and waits for all admitted publications to leave.
     *
     * <p>This method is idempotent.</p>
     *
     * @param timeout maximum drain wait
     * @return {@code true} when drained, or {@code false} when the timeout elapsed
     */
    public boolean retireAndAwaitDrain(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        if (admission.retireWithoutActivePublications()) {
            return true;
        }

        long timeoutNanos = saturatedNanos(timeout);
        long started = System.nanoTime();
        boolean interrupted = false;
        synchronized (drained) {
            while (admission.hasActivePublications()) {
                long remaining = timeoutNanos - (System.nanoTime() - started);
                if (remaining <= 0) {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return false;
                }
                try {
                    long millis = remaining / 1_000_000L;
                    int nanos = (int) (remaining % 1_000_000L);
                    drained.wait(millis, nanos);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /**
     * Returns whether retirement has begun.
     *
     * @return {@code true} after the gate stops accepting publications
     */
    public boolean retired() {
        return admission.retired();
    }

    private static long saturatedNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
