package com.zsumz.logyard.runtime.installation;

import java.util.EnumMap;

/** Mutable lease accounting owned exclusively by the installation manager lock. */
final class RuntimeLeaseCounts {
    private final EnumMap<RuntimeOwner, Integer> counts = new EnumMap<>(RuntimeOwner.class);

    RuntimeLeaseCounts() {
        for (RuntimeOwner owner : RuntimeOwner.values()) {
            counts.put(owner, 0);
        }
    }

    int get(RuntimeOwner owner) {
        return counts.get(owner);
    }

    void increment(RuntimeOwner owner) {
        counts.put(owner, Math.addExact(counts.get(owner), 1));
    }

    void decrement(RuntimeOwner owner) {
        int count = counts.get(owner);
        if (count <= 0) {
            throw new IllegalStateException("Logyard runtime lease accounting underflow for " + owner);
        }
        counts.put(owner, count - 1);
    }

    int total() {
        int total = 0;
        for (int count : counts.values()) {
            total = Math.addExact(total, count);
        }
        return total;
    }

    void clear() {
        counts.replaceAll((owner, ignored) -> 0);
    }
}
