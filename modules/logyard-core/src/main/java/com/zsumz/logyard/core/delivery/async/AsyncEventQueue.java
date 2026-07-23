package com.zsumz.logyard.core.delivery.async;

import com.zsumz.logyard.api.event.LogEvent;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Bounded event queue whose quiescence barrier includes events already claimed by the worker.
 *
 * <p>An accepted event remains outstanding from the start of an offer until the worker explicitly
 * completes its claim. This closes the flush race where an event has left the queue but has not yet
 * reached the delegate.</p>
 */
final class AsyncEventQueue {
    private final ArrayBlockingQueue<LogEvent> events;
    private final AtomicInteger outstanding = new AtomicInteger();

    AsyncEventQueue(int capacity) {
        events = new ArrayBlockingQueue<>(capacity);
    }

    OfferResult offerImmediately(LogEvent event, BooleanSupplier accepting) {
        outstanding.incrementAndGet();
        if (!events.offer(event)) {
            outstanding.decrementAndGet();
            return OfferResult.FULL;
        }
        return retainAcceptedOffer(event, accepting);
    }

    OfferResult offerWithin(LogEvent event, Duration wait, BooleanSupplier accepting) throws InterruptedException {
        outstanding.incrementAndGet();
        boolean offered = false;
        try {
            offered = events.offer(event, saturatedNanos(wait), TimeUnit.NANOSECONDS);
            return offered ? retainAcceptedOffer(event, accepting) : OfferResult.FULL;
        } finally {
            if (!offered) {
                outstanding.decrementAndGet();
            }
        }
    }

    LogEvent claimNow() {
        return events.poll();
    }

    LogEvent claimWithin(long timeout, TimeUnit unit) throws InterruptedException {
        return events.poll(timeout, unit);
    }

    void completeClaims(int count) {
        outstanding.addAndGet(-count);
    }

    boolean awaitQuiescence(Duration timeout) {
        long timeoutNanos = saturatedNanos(timeout);
        long started = System.nanoTime();
        while (outstanding.get() != 0) {
            long elapsed = System.nanoTime() - started;
            if (elapsed >= timeoutNanos) {
                return false;
            }
            LockSupport.parkNanos(Math.min(100_000L, timeoutNanos - elapsed));
        }
        return true;
    }

    int capacity() {
        return events.size() + events.remainingCapacity();
    }

    int queued() {
        return events.size();
    }

    int outstanding() {
        return outstanding.get();
    }

    boolean isEmpty() {
        return events.isEmpty();
    }

    private OfferResult retainAcceptedOffer(LogEvent event, BooleanSupplier accepting) {
        if (!accepting.getAsBoolean() && events.remove(event)) {
            outstanding.decrementAndGet();
            return OfferResult.CLOSED;
        }
        return OfferResult.ENQUEUED;
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    enum OfferResult {
        ENQUEUED,
        FULL,
        CLOSED
    }
}
