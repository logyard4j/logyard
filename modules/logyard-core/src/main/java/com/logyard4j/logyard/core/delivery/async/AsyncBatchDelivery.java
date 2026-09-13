package com.logyard4j.logyard.core.delivery.async;

import com.logyard4j.logyard.api.event.LogEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Forms one bounded batch and completes every queue claim after delegate delivery. */
final class AsyncBatchDelivery {
    private final AsyncEventQueue eventQueue;
    private final AsyncBatchPolicy policy;
    private final AsyncDelegateDelivery delivery;

    AsyncBatchDelivery(
            AsyncEventQueue eventQueue,
            AsyncBatchPolicy policy,
            AsyncDelegateDelivery delivery) {
        this.eventQueue = Objects.requireNonNull(eventQueue, "eventQueue");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    /**
     * Delivers the first claimed event with any immediately or timely available followers.
     *
     * @param first event already claimed by the worker
     * @param timedClaimer worker-owned timed queue claimant
     * @return whether timed collection was interrupted before the batch deadline
     */
    boolean deliver(LogEvent first, TimedEventClaimer timedClaimer) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(timedClaimer, "timedClaimer");
        List<LogEvent> events = new ArrayList<>(policy.maximumSize());
        events.add(first);
        boolean interrupted = false;
        try {
            long deadline = deadline();
            while (events.size() < policy.maximumSize()) {
                LogEvent next;
                if (policy.maximumDelayNanos() == 0L) {
                    next = eventQueue.claimNow();
                } else {
                    try {
                        next = claimBefore(deadline, timedClaimer);
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                        break;
                    }
                }
                if (next == null) {
                    break;
                }
                events.add(next);
            }
            delivery.deliverBatch(events);
        } finally {
            eventQueue.completeClaims(events.size());
        }
        return interrupted;
    }

    private long deadline() {
        return policy.maximumDelayNanos() == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : saturatedAdd(System.nanoTime(), policy.maximumDelayNanos());
    }

    private static LogEvent claimBefore(long deadline, TimedEventClaimer timedClaimer) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        return remaining <= 0L ? null : timedClaimer.claimWithin(remaining);
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        return result < left ? Long.MAX_VALUE : result;
    }

    @FunctionalInterface
    interface TimedEventClaimer {
        LogEvent claimWithin(long timeoutNanos) throws InterruptedException;
    }
}
