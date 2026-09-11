package com.logyard4j.core.delivery.async;

import com.logyard4j.api.event.LogEvent;
import com.logyard4j.core.failure.ComponentInvocationBoundary;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns queue draining, batch formation, delegate delivery, and asynchronous delivery accounting. */
final class AsyncDeliveryLoop {
    private final AsyncEventQueue eventQueue;
    private final AsyncSinkMetrics metrics;
    private final AsyncDropReporter dropReporter;
    private final AsyncSinkDiagnostics diagnostics;
    private final AsyncDelegateDelivery delivery;
    private final AsyncWorkerLifecycle lifecycle;
    private final AsyncBatchPolicy batching;
    private final AsyncBatchDelivery batchDelivery;
    private final AtomicInteger activeDeliveries = new AtomicInteger();

    AsyncDeliveryLoop(
            String name,
            AsyncEventQueue eventQueue,
            AsyncSinkMetrics metrics,
            AsyncSinkDiagnostics diagnostics,
            AsyncDelegateDelivery delivery,
            AsyncWorkerLifecycle lifecycle) {
        this.eventQueue = Objects.requireNonNull(eventQueue, "eventQueue");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        dropReporter = new AsyncDropReporter(metrics);
        batching = AsyncBatchPolicy.from(name, delivery);
        batchDelivery = new AsyncBatchDelivery(eventQueue, batching, delivery);
    }

    void run() {
        while (lifecycle.acceptingEvents() || !eventQueue.isEmpty()) {
            try {
                AsyncEventQueue.QueueEntry entry = eventQueue.claimNextWithin(100, TimeUnit.MILLISECONDS);
                if (entry instanceof AsyncEventQueue.QueuedEvent event) {
                    if (batching.enabled()) {
                        deliverBatch(event.event());
                    } else {
                        deliverApplicationEvent(event.event(), true);
                    }
                }
                emitDropSummary(false);
            } catch (InterruptedException ignored) {
                // Preserve interruption semantics from an external worker interruption.
            } catch (Throwable failure) {
                ComponentInvocationBoundary.report(
                        "async output worker",
                        failure,
                        diagnostics::failure);
            }
        }
    }

    int activeDeliveries() {
        return activeDeliveries.get();
    }

    boolean awaitQuiescence(Duration timeout) {
        return eventQueue.awaitQuiescence(timeout);
    }

    boolean batchingEnabled() {
        return batching.enabled();
    }

    int maximumBatchSize() {
        return batching.maximumSize();
    }

    void deliverOnCallerThread(LogEvent event) {
        deliverApplicationEvent(event, false);
    }

    void drainQueueToEmergency(String reason, OverflowPolicy overflowPolicy) {
        LogEvent remaining;
        while ((remaining = eventQueue.claimNow()) != null) {
            eventQueue.completeClaims(1);
            if (overflowPolicy.dropsUndelivered(remaining.level())) {
                metrics.recordDrop(remaining.level());
            } else {
                metrics.recordEmergencyFallback();
                diagnostics.emergency(remaining, reason);
            }
        }
    }

    void closeDelegate(String name) {
        emitDropSummary(true);
        delivery.close(name);
    }

    private void deliverBatch(LogEvent first) {
        activeDeliveries.incrementAndGet();
        boolean interrupted = false;
        try {
            interrupted = batchDelivery.deliver(first, this::claimBatchFollower);
        } finally {
            activeDeliveries.decrementAndGet();
            if (interrupted && lifecycle.acceptingEvents()) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private LogEvent claimBatchFollower(long timeoutNanos) throws InterruptedException {
        return eventQueue.claimWithin(timeoutNanos, TimeUnit.NANOSECONDS);
    }

    private void deliverApplicationEvent(LogEvent event, boolean queuedDelivery) {
        activeDeliveries.incrementAndGet();
        try {
            delivery.deliverEvent(event);
        } finally {
            if (queuedDelivery) {
                eventQueue.completeClaims(1);
            }
            activeDeliveries.decrementAndGet();
        }
    }

    private void emitDropSummary(boolean force) {
        LogEvent report = dropReporter.nextReport(force);
        if (report != null) {
            delivery.deliverInternalEvent(report);
        }
    }
}
