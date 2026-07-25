package com.zsumz.logyard.runtime.installation;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Non-blocking lifecycle state machine for one managed runtime installation. */
final class RuntimeInstallationTransitions {
    enum Phase {
        OPEN,
        RECONFIGURING,
        RELOADING,
        CLOSED
    }

    private final AtomicReference<Snapshot> state = new AtomicReference<>(new Snapshot(Phase.OPEN, null));

    void initialize(ActiveRuntimeConfiguration active) {
        Snapshot initial = state.get();
        if (initial.phase() != Phase.OPEN || initial.active() != null
                || !state.compareAndSet(initial, new Snapshot(Phase.OPEN, Objects.requireNonNull(active, "active")))) {
            throw new IllegalStateException("runtime installation was initialized more than once");
        }
    }

    ActiveRuntimeConfiguration begin(Phase requested) {
        if (requested == Phase.OPEN || requested == Phase.CLOSED) {
            throw new IllegalArgumentException("transition phase must own an operation");
        }
        while (true) {
            Snapshot current = state.get();
            if (current.phase() != Phase.OPEN) {
                return null;
            }
            if (state.compareAndSet(current, new Snapshot(requested, current.active()))) {
                return current.active();
            }
        }
    }

    boolean accepts(Phase expected, ActiveRuntimeConfiguration active) {
        Snapshot current = state.get();
        return current.phase() == expected && current.active() == active;
    }

    boolean finish(Phase expected) {
        while (true) {
            Snapshot current = state.get();
            if (current.phase() != expected) {
                return false;
            }
            if (state.compareAndSet(current, new Snapshot(Phase.OPEN, current.active()))) {
                return true;
            }
        }
    }

    boolean replace(
            Phase expected,
            ActiveRuntimeConfiguration active,
            ActiveRuntimeConfiguration replacement) {
        while (true) {
            Snapshot current = state.get();
            if (current.phase() != expected || current.active() != active) {
                return false;
            }
            if (state.compareAndSet(current, new Snapshot(Phase.OPEN, Objects.requireNonNull(replacement, "replacement")))) {
                return true;
            }
        }
    }

    boolean replaceDuring(
            Phase expected,
            ActiveRuntimeConfiguration active,
            ActiveRuntimeConfiguration replacement) {
        while (true) {
            Snapshot current = state.get();
            if (current.phase() != expected || current.active() != active) {
                return false;
            }
            if (state.compareAndSet(current, new Snapshot(expected, Objects.requireNonNull(replacement, "replacement")))) {
                return true;
            }
        }
    }

    CloseTransition close() {
        while (true) {
            Snapshot current = state.get();
            if (current.phase() == Phase.CLOSED) {
                return new CloseTransition(false, null);
            }
            if (state.compareAndSet(current, new Snapshot(Phase.CLOSED, null))) {
                return new CloseTransition(true, current.active());
            }
        }
    }

    ActiveRuntimeConfiguration current() {
        return state.get().active();
    }

    boolean closed() {
        return state.get().phase() == Phase.CLOSED;
    }

    private record Snapshot(Phase phase, ActiveRuntimeConfiguration active) {
        Snapshot {
            Objects.requireNonNull(phase, "phase");
        }
    }

    record CloseTransition(boolean changed, ActiveRuntimeConfiguration active) {
    }
}
