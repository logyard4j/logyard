package com.zsumz.logyard.slf4j.internal.factory;

import java.util.Objects;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

/** Stable provider-facing proxy whose delegate is installed exactly once. */
public final class SwitchableLoggerFactory implements ILoggerFactory {
    private volatile ILoggerFactory delegate;
    private volatile Throwable failure;

    public synchronized void install(ILoggerFactory replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (delegate != null) {
            if (delegate != replacement) {
                throw new IllegalStateException("Logyard SLF4J logger factory is already installed");
            }
            return;
        }
        if (failure != null) {
            throw new IllegalStateException("Logyard SLF4J provider initialization already failed", failure);
        }
        delegate = replacement;
    }

    public synchronized void fail(Throwable initializationFailure) {
        if (delegate == null && failure == null) {
            failure = Objects.requireNonNull(initializationFailure, "initializationFailure");
        }
    }

    @Override
    public Logger getLogger(String name) {
        ILoggerFactory current = delegate;
        if (current != null) {
            return current.getLogger(name);
        }
        Throwable initializationFailure = failure;
        if (initializationFailure != null) {
            throw new IllegalStateException(
                    "Logyard SLF4J provider failed to initialize", initializationFailure);
        }
        throw new IllegalStateException("Logyard SLF4J provider has not been initialized");
    }
}
