package com.zsumz.logyard.api.failure;

import java.util.Objects;

/** Fatal-error classification and interrupt handling shared by logging isolation boundaries. */
public final class FailureIsolation {
    private FailureIsolation() {
    }

    /**
     * Re-throws failures after which continuing inside the JVM is unsafe.
     *
     * @param failure failure to classify
     */
    @SuppressWarnings("removal")
    public static void rethrowIfFatal(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
        if (failure instanceof LinkageError fatal) {
            throw fatal;
        }
    }

    /**
     * Restores the interrupted flag when an extension uses a sneaky checked throw.
     *
     * @param failure isolated failure
     */
    public static void restoreInterrupt(Throwable failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Prepares to recover from an ordinary failure by rethrowing fatal JVM failures and preserving interruption.
     *
     * @param failure failure crossing an isolation boundary
     */
    public static void prepareForRecovery(Throwable failure) {
        rethrowIfFatal(failure);
        restoreInterrupt(failure);
    }
}
