package com.zsumz.logyard.spring.boot.internal.lifecycle;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.runtime.bootstrap.ConfigurationDiscovery;
import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;
import com.zsumz.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import com.zsumz.logyard.runtime.bootstrap.RuntimeOwner;
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

    private final JulCapture julCapture = new JulCapture();
    private RuntimeBundle bundle;
    private Phase phase = Phase.IDLE;
    private long generation;

    public void startEarly() {
        long reservedGeneration;
        synchronized (this) {
            if (phase == Phase.ACTIVE && bundle != null) {
                return;
            }
            requirePhase(Phase.IDLE);
            phase = Phase.STARTING;
            reservedGeneration = ++generation;
        }

        RuntimeBundle acquired = null;
        boolean captureStarted = false;
        try {
            acquired = LogyardBootstrap.acquire(RuntimeOwner.FRAMEWORK, ConfigurationDiscovery.resolve());
            julCapture.start(acquired.runtime());
            captureStarted = true;
            synchronized (this) {
                requireReservation(Phase.STARTING, reservedGeneration);
                bundle = acquired;
                phase = Phase.ACTIVE;
            }
        } catch (Throwable failure) {
            if (captureStarted) {
                SpringLifecycleBoundary.invoke("failed early JUL capture release", julCapture::close);
            }
            if (acquired != null) {
                RuntimeBundle rejected = acquired;
                SpringLifecycleBoundary.invoke("failed early runtime lease release", rejected::close);
            }
            restoreAfterFailure(Phase.STARTING, reservedGeneration, null);
            throw failure;
        }
    }

    public void configure(LogyardConfigurationSource source) {
        Objects.requireNonNull(source, "source");
        RuntimeBundle previous;
        long reservedGeneration;
        synchronized (this) {
            if (phase != Phase.IDLE && phase != Phase.ACTIVE) {
                throw lifecycleFailure(phase);
            }
            previous = bundle;
            phase = Phase.CONFIGURING;
            reservedGeneration = ++generation;
        }

        RuntimeBundle replacement = null;
        boolean captureStarted = false;
        try {
            replacement = LogyardBootstrap.acquire(RuntimeOwner.FRAMEWORK, source);
            julCapture.start(replacement.runtime());
            captureStarted = previous == null;
            synchronized (this) {
                requireReservation(Phase.CONFIGURING, reservedGeneration);
                bundle = replacement;
                phase = Phase.ACTIVE;
            }
        } catch (Throwable failure) {
            if (captureStarted) {
                SpringLifecycleBoundary.invoke("failed configured JUL capture release", julCapture::close);
            }
            if (replacement != null) {
                RuntimeBundle rejected = replacement;
                SpringLifecycleBoundary.invoke("failed configured runtime lease release", rejected::close);
            }
            restoreAfterFailure(Phase.CONFIGURING, reservedGeneration, previous);
            throw failure;
        }
        if (previous != null) {
            SpringLifecycleBoundary.invoke("early runtime lease release", previous::close);
        }
    }

    public LogyardRuntime runtime() {
        while (true) {
            RuntimeBundle current;
            synchronized (this) {
                if (phase == Phase.ACTIVE && bundle != null) {
                    current = bundle;
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
        RuntimeBundle closing;
        long reservedGeneration;
        synchronized (this) {
            if (phase == Phase.IDLE) {
                return;
            }
            if (phase == Phase.CLOSING) {
                return;
            }
            phase = Phase.CLOSING;
            reservedGeneration = ++generation;
            closing = bundle;
            bundle = null;
        }
        if (closing != null) {
            SpringLifecycleBoundary.invoke("Spring JUL capture release", julCapture::close);
            SpringLifecycleBoundary.invoke("Spring shutdown flush", () -> closing.runtime().flush());
            SpringLifecycleBoundary.invoke("Spring runtime lease release", closing::close);
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
            RuntimeBundle previous) {
        if (phase == expected && generation == expectedGeneration) {
            bundle = previous;
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
