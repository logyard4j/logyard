package com.logyard4j.logyard.spring.boot.internal.lifecycle;

import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.runtime.bootstrap.ConfigurationDiscovery;
import com.logyard4j.logyard.runtime.bootstrap.LogyardBootstrap;
import com.logyard4j.logyard.runtime.bootstrap.LogyardConfigurationSource;

import java.util.Objects;

/** Owns one replaceable Spring lease without ever replacing the shared runtime identity. */
public final class FrameworkRuntimeLifecycle {
    private enum Phase {
        IDLE,
        STARTING,
        ACTIVE,
        CONFIGURING,
        CLOSING
    }

    private final FrameworkRuntimeAcquirer runtimeAcquirer;
    private final JulCapture julCapture = new JulCapture();
    private FrameworkRuntimeLease lease;
    private Phase phase = Phase.IDLE;
    private long generation;

    public FrameworkRuntimeLifecycle() {
        this(LogyardBootstrap::acquire);
    }

    FrameworkRuntimeLifecycle(FrameworkRuntimeAcquirer runtimeAcquirer) {
        this.runtimeAcquirer = Objects.requireNonNull(runtimeAcquirer, "runtimeAcquirer");
    }

    public void startEarly() {
        long reservedGeneration;
        synchronized (this) {
            if (phase == Phase.ACTIVE && lease != null) {
                return;
            }
            requirePhase(Phase.IDLE);
            phase = Phase.STARTING;
            reservedGeneration = ++generation;
        }

        FrameworkRuntimeLease acquired = null;
        try {
            acquired = FrameworkRuntimeLease.acquire(
                    runtimeAcquirer,
                    julCapture,
                    ConfigurationDiscovery.resolve(),
                    "failed early JUL capture release",
                    "failed early runtime lease release");
            synchronized (this) {
                requireReservation(Phase.STARTING, reservedGeneration);
                lease = acquired;
                phase = Phase.ACTIVE;
            }
        } catch (Throwable failure) {
            if (acquired != null) {
                acquired.release("failed early JUL capture release", "failed early runtime lease release");
            }
            restoreAfterFailure(Phase.STARTING, reservedGeneration, null);
            throw failure;
        }
    }

    public void configure(LogyardConfigurationSource source) {
        Objects.requireNonNull(source, "source");
        FrameworkRuntimeLease previous;
        long reservedGeneration;
        synchronized (this) {
            if (phase != Phase.IDLE && phase != Phase.ACTIVE) {
                throw lifecycleFailure(phase);
            }
            previous = lease;
            phase = Phase.CONFIGURING;
            reservedGeneration = ++generation;
        }

        FrameworkRuntimeLease replacement = null;
        try {
            replacement = FrameworkRuntimeLease.acquire(
                    runtimeAcquirer,
                    julCapture,
                    source,
                    "failed configured JUL capture release",
                    "failed configured runtime lease release");
            synchronized (this) {
                requireReservation(Phase.CONFIGURING, reservedGeneration);
                lease = replacement;
                phase = Phase.ACTIVE;
            }
        } catch (Throwable failure) {
            if (replacement != null) {
                replacement.release("failed configured JUL capture release", "failed configured runtime lease release");
            }
            restoreAfterFailure(Phase.CONFIGURING, reservedGeneration, previous);
            throw failure;
        }
        if (previous != null) {
            previous.release("early JUL capture release", "early runtime lease release");
        }
    }

    public LogyardRuntime runtime() {
        while (true) {
            FrameworkRuntimeLease current;
            synchronized (this) {
                if (phase == Phase.ACTIVE && lease != null) {
                    current = lease;
                } else if (phase == Phase.IDLE) {
                    current = null;
                } else {
                    throw lifecycleFailure(phase);
                }
            }
            if (current != null) {
                return current.runtime();
            }
            startEarly();
        }
    }

    public void close() {
        FrameworkRuntimeLease closing;
        long reservedGeneration;
        synchronized (this) {
            if (phase == Phase.IDLE || phase == Phase.CLOSING) {
                return;
            }
            phase = Phase.CLOSING;
            reservedGeneration = ++generation;
            closing = lease;
            lease = null;
        }
        if (closing != null) {
            closing.close("Spring JUL capture release", "Spring shutdown flush", "Spring runtime lease release");
        }
        synchronized (this) {
            if (phase == Phase.CLOSING && generation == reservedGeneration) {
                phase = Phase.IDLE;
            }
        }
    }

    private synchronized void restoreAfterFailure(
            Phase expected,
            long expectedGeneration,
            FrameworkRuntimeLease previous) {
        if (phase == expected && generation == expectedGeneration) {
            lease = previous;
            phase = previous == null ? Phase.IDLE : Phase.ACTIVE;
        }
    }

    private void requireReservation(Phase expected, long expectedGeneration) {
        if (phase != expected || generation != expectedGeneration) {
            throw new IllegalStateException("Spring Logyard lifecycle operation was superseded by " + phase.name().toLowerCase());
        }
    }

    private void requirePhase(Phase expected) {
        if (phase != expected) {
            throw lifecycleFailure(phase);
        }
    }

    private static IllegalStateException lifecycleFailure(Phase current) {
        return new IllegalStateException(
                "Spring Logyard lifecycle is " + current.name().toLowerCase() + "; concurrent lifecycle access is not allowed");
    }
}
