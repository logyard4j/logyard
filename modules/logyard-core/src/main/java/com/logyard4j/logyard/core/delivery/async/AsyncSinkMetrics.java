package com.logyard4j.logyard.core.delivery.async;

import com.logyard4j.logyard.api.Level;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe telemetry owned by one asynchronous output. */
final class AsyncSinkMetrics {
    private final EnumMap<Level, LongAdder> droppedByLevel = countersByLevel();
    private final EnumMap<Level, LongAdder> pendingDropReports = countersByLevel();
    private final LongAdder enqueued = new LongAdder();
    private final LongAdder delivered = new LongAdder();
    private final LongAdder synchronousFallbacks = new LongAdder();
    private final LongAdder emergencyFallbacks = new LongAdder();

    void recordEnqueued() {
        enqueued.increment();
    }

    void recordDelivered() {
        delivered.increment();
    }

    void recordDelivered(int count) {
        delivered.add(count);
    }

    void recordDrop(Level level) {
        droppedByLevel.get(level).increment();
        pendingDropReports.get(level).increment();
    }

    void recordSynchronousFallback() {
        synchronousFallbacks.increment();
    }

    void recordEmergencyFallback() {
        emergencyFallbacks.increment();
    }

    void recordEmergencyFallbacks(int count) {
        emergencyFallbacks.add(count);
    }

    long dropped(Level level) {
        return droppedByLevel.get(level).sum();
    }

    long enqueued() {
        return enqueued.sum();
    }

    long delivered() {
        return delivered.sum();
    }

    long synchronousFallbacks() {
        return synchronousFallbacks.sum();
    }

    long emergencyFallbacks() {
        return emergencyFallbacks.sum();
    }

    Map<Level, Long> drainPendingDropReport() {
        EnumMap<Level, Long> snapshot = new EnumMap<>(Level.class);
        for (Level level : Level.values()) {
            long count = pendingDropReports.get(level).sumThenReset();
            if (count > 0L) {
                snapshot.put(level, count);
            }
        }
        return snapshot;
    }

    Snapshot snapshot() {
        long dropped = 0L;
        for (LongAdder counter : droppedByLevel.values()) {
            dropped += counter.sum();
        }
        return new Snapshot(enqueued(), delivered(), dropped, synchronousFallbacks(), emergencyFallbacks());
    }

    private static EnumMap<Level, LongAdder> countersByLevel() {
        EnumMap<Level, LongAdder> counters = new EnumMap<>(Level.class);
        for (Level level : Level.values()) {
            counters.put(level, new LongAdder());
        }
        return counters;
    }

    record Snapshot(long enqueued, long delivered, long dropped, long synchronousFallbacks, long emergencyFallbacks) {
    }
}
