package com.logyard4j.core.delivery.async;

import com.logyard4j.api.event.LogEvent;

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
 * completes its claim. A typed wake-up entry releases a blocked worker during draining without
 * interrupting delegate code.</p>
 */
final class AsyncEventQueue {
    private final int capacity;
    private final ArrayBlockingQueue<QueueEntry> entries;
    private final AtomicInteger queuedEvents = new AtomicInteger();
    private final AtomicInteger outstanding = new AtomicInteger();

    AsyncEventQueue(int capacity) {
        entries = new ArrayBlockingQueue<>(capacity);
        this.capacity = capacity;
    }

    OfferResult offerImmediately(LogEvent event, BooleanSupplier accepting) {
        QueuedEvent entry = new QueuedEvent(event);
        reserveEventSlot();
        if (!entries.offer(entry)) {
            releaseEventSlot();
            return OfferResult.FULL;
        }
        return retainAcceptedOffer(entry, accepting);
    }

    OfferResult offerWithin(LogEvent event, Duration wait, BooleanSupplier accepting) throws InterruptedException {
        QueuedEvent entry = new QueuedEvent(event);
        reserveEventSlot();
        try {
            if (!entries.offer(entry, saturatedNanos(wait), TimeUnit.NANOSECONDS)) {
                releaseEventSlot();
                return OfferResult.FULL;
            }
            return retainAcceptedOffer(entry, accepting);
        } catch (InterruptedException interruption) {
            releaseEventSlot();
            throw interruption;
        }
    }

    QueueEntry claimNextWithin(long timeout, TimeUnit unit) throws InterruptedException {
        return recordClaim(entries.poll(timeout, unit));
    }

    LogEvent claimNow() {
        return eventFrom(recordClaim(entries.poll()));
    }

    LogEvent claimWithin(long timeout, TimeUnit unit) throws InterruptedException {
        return eventFrom(claimNextWithin(timeout, unit));
    }

    void wakeWorker() {
        entries.offer(WakeUp.INSTANCE);
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
        return capacity;
    }

    int queued() {
        return queuedEvents.get();
    }

    int outstanding() {
        return outstanding.get();
    }

    boolean isEmpty() {
        return queuedEvents.get() == 0;
    }

    private void reserveEventSlot() {
        outstanding.incrementAndGet();
        queuedEvents.incrementAndGet();
    }

    private void releaseEventSlot() {
        outstanding.decrementAndGet();
        queuedEvents.decrementAndGet();
    }

    private OfferResult retainAcceptedOffer(QueuedEvent entry, BooleanSupplier accepting) {
        if (!accepting.getAsBoolean() && entries.remove(entry)) {
            releaseEventSlot();
            return OfferResult.CLOSED;
        }
        return OfferResult.ENQUEUED;
    }

    private QueueEntry recordClaim(QueueEntry entry) {
        if (entry instanceof QueuedEvent) {
            queuedEvents.decrementAndGet();
        }
        return entry;
    }

    private static LogEvent eventFrom(QueueEntry entry) {
        return entry instanceof QueuedEvent event ? event.event() : null;
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    interface QueueEntry {
    }

    record QueuedEvent(LogEvent event) implements QueueEntry {
    }

    enum WakeUp implements QueueEntry {
        INSTANCE
    }

    enum OfferResult {
        ENQUEUED,
        FULL,
        CLOSED
    }
}
