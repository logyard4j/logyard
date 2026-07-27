package com.zsumz.logyard.runtime.reload.coordination;

import com.zsumz.logyard.runtime.assembly.RuntimeAssembly;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns only immutable reload state and a non-blocking single-writer reservation. */
final class ReloadState {
    private final AtomicReference<ActiveConfiguration> active;
    private final AtomicBoolean writerReserved = new AtomicBoolean();

    ReloadState(ConfigurationSnapshot snapshot, RuntimeAssembly assembly) {
        active = new AtomicReference<>(new ActiveConfiguration(snapshot, assembly, 0L));
    }

    Reservation tryReserve() {
        return writerReserved.compareAndSet(false, true) ? new Reservation(active.get()) : null;
    }

    boolean commit(Reservation reservation, ConfigurationSnapshot snapshot, RuntimeAssembly assembly) {
        ActiveConfiguration expected = Objects.requireNonNull(reservation, "reservation").active();
        ActiveConfiguration next = new ActiveConfiguration(snapshot, assembly, expected.generation() + 1L);
        return active.compareAndSet(expected, next);
    }

    void release(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        writerReserved.set(false);
    }

    ActiveConfiguration current() {
        return active.get();
    }

    record ActiveConfiguration(ConfigurationSnapshot snapshot, RuntimeAssembly assembly, long generation) {
        ActiveConfiguration {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(assembly, "assembly");
        }
    }

    record Reservation(ActiveConfiguration active) {
        Reservation {
            Objects.requireNonNull(active, "active");
        }
    }
}
