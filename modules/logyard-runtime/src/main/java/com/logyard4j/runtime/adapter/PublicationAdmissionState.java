package com.logyard4j.runtime.adapter;

import java.util.concurrent.atomic.AtomicInteger;

/** Atomic publication admission state that combines retirement with the active publication count. */
final class PublicationAdmissionState {
    private static final int RETIRED = 1 << 31;
    private static final int ACTIVE_MASK = ~RETIRED;

    private final AtomicInteger snapshot = new AtomicInteger();

    boolean tryEnter() {
        while (true) {
            int current = snapshot.get();
            if ((current & RETIRED) != 0) {
                return false;
            }
            if ((current & ACTIVE_MASK) == ACTIVE_MASK) {
                throw new IllegalStateException("too many concurrent adapter publications");
            }
            if (snapshot.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    boolean exitAndRetiredGateIsDrained() {
        int remaining = snapshot.decrementAndGet();
        return (remaining & RETIRED) != 0 && (remaining & ACTIVE_MASK) == 0;
    }

    boolean retireWithoutActivePublications() {
        int previous = snapshot.getAndUpdate(current -> current | RETIRED);
        return (previous & ACTIVE_MASK) == 0;
    }

    boolean hasActivePublications() {
        return (snapshot.get() & ACTIVE_MASK) != 0;
    }

    boolean retired() {
        return (snapshot.get() & RETIRED) != 0;
    }
}
