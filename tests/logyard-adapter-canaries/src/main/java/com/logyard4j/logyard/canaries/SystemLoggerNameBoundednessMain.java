package com.logyard4j.logyard.canaries;

import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.runtime.adapter.LazyAdapterRuntime;
import com.logyard4j.logyard.systemlogger.internal.factory.LogyardSystemLogger;
import com.logyard4j.logyard.systemlogger.internal.factory.LogyardSystemLoggerRegistry;

/** Constrained-heap probe for caller-supplied System.Logger names. */
public final class SystemLoggerNameBoundednessMain {
    private SystemLoggerNameBoundednessMain() {
    }

    public static void main(String[] args) {
        rejectDirectName();
        rejectFinderName();
        System.out.println("System.Logger name boundedness verification passed under constrained heap");
    }

    private static void rejectDirectName() {
        String name = " x".repeat(17_500_000);
        expectRejection(() -> new LogyardSystemLogger(
                name, SystemLoggerNameBoundednessMain.class.getModule(), new LazyAdapterRuntime("system-logger")));
    }

    private static void rejectFinderName() {
        String name = " x".repeat(17_500_000);
        LogyardSystemLoggerRegistry registry = new LogyardSystemLoggerRegistry(new LazyAdapterRuntime("system-logger"));
        expectRejection(() -> registry.logger(name, SystemLoggerNameBoundednessMain.class.getModule()));
        if (registry.size() != 0) {
            throw new AssertionError("rejected name entered the finder cache");
        }
    }

    private static void expectRejection(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("oversized System.Logger name was accepted");
        } catch (IllegalArgumentException expected) {
            String message = "logger name exceeds " + CaptureLimits.MAX_NAME_CHARS + " characters";
            if (!expected.getMessage().contains(message)) {
                throw new AssertionError("System.Logger rejection lost its public message", expected);
            }
        }
    }
}
