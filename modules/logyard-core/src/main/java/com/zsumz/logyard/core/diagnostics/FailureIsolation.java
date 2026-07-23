package com.zsumz.logyard.core.diagnostics;

import java.util.Objects;

/** Fatal-error and interrupt handling shared by extension isolation boundaries. */
public final class FailureIsolation {
    private FailureIsolation() {
    }

    /** Re-throws failures after which continuing inside the JVM is unsafe. */
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

    /** Restores the interrupted flag when an extension uses a sneaky checked throw. */
    public static void restoreInterrupt(Throwable failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
