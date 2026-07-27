package com.zsumz.logyard.slf4j.internal.factory;

import java.util.Objects;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

/** Stable provider-facing proxy whose delegate is installed exactly once. */
public final class SwitchableLoggerFactory implements ILoggerFactory {
    private volatile FactoryState state = AwaitingInstallation.INSTANCE;

    public synchronized void install(ILoggerFactory replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (state instanceof Installed installed) {
            if (installed.delegate() != replacement) {
                throw new IllegalStateException("Logyard SLF4J logger factory is already installed");
            }
            return;
        }
        if (state instanceof Failed failed) {
            throw new IllegalStateException("Logyard SLF4J provider initialization already failed", failed.failure());
        }
        state = new Installed(replacement);
    }

    public synchronized void fail(Throwable initializationFailure) {
        if (state == AwaitingInstallation.INSTANCE) {
            state = new Failed(Objects.requireNonNull(initializationFailure, "initializationFailure"));
        }
    }

    @Override
    public Logger getLogger(String name) {
        FactoryState current = state;
        if (current instanceof Installed installed) {
            return installed.delegate().getLogger(name);
        }
        if (current instanceof Failed failed) {
            throw new IllegalStateException("Logyard SLF4J provider failed to initialize", failed.failure());
        }
        throw new IllegalStateException("Logyard SLF4J provider has not been initialized");
    }

    private sealed interface FactoryState permits AwaitingInstallation, Failed, Installed {
    }

    private enum AwaitingInstallation implements FactoryState {
        INSTANCE
    }

    private record Failed(Throwable failure) implements FactoryState {
    }

    private record Installed(ILoggerFactory delegate) implements FactoryState {
    }
}
