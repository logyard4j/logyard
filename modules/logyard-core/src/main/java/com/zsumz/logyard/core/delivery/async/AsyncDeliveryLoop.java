package com.zsumz.logyard.core.delivery.async;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.core.failure.ComponentInvocationBoundary;

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
    private final AsyncBatchPolicy batching;
    private final AsyncBatchDelivery batchDelivery;
    private final AtomicInteger activeDeliveries = new AtomicInteger();
    private volatile boolean running = true;
    private volatile boolean polling;

    AsyncDeliveryLoop(
            String name,
            AsyncEventQueue eventQueue,
            AsyncSinkMetrics metrics,
            AsyncSinkDiagnostics diagnostics,
            AsyncDelegateDelivery delivery) {
        this.eventQueue = Objects.requireNonNull(eventQueue, "eventQueue");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        dropReporter = new AsyncDropReporter(metrics);
        batching = AsyncBatchPolicy.from(name, delivery);
        batchDelivery = new AsyncBatchDelivery(eventQueue, batching, delivery);
    }

    void run() {
        while (running || !eventQueue.isEmpty()) {
            try {
                LogEvent event = claimNext();
                if (event != null) {
                    if (batching.enabled()) {
                        deliverBatch(event);
                    } else {
                        deliverApplicationEvent(event, true);
                    }
                }
                emitDropSummary(false);
            } catch (InterruptedException ignored) {
                // Worker shutdown interrupts only the queue poll to begin draining immediately.
            } catch (Throwable failure) {
                ComponentInvocationBoundary.report(
                        "async output worker",
                        failure,
                        diagnostics::failure);
            }
        }
    }

    void stop() {
        running = false;
    }

    boolean running() {
        return running;
    }

    boolean polling() {
        return polling;
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

    void drainQueueToEmergency(String reason) {
        LogEvent remaining;
        while ((remaining = eventQueue.claimNow()) != null) {
            eventQueue.completeClaims(1);
            metrics.recordEmergencyFallback();
            diagnostics.emergency(remaining, reason);
        }
    }

    void closeDelegate(String name) {
        emitDropSummary(true);
        delivery.close(name);
    }

    private LogEvent claimNext() throws InterruptedException {
        polling = true;
        try {
            return eventQueue.claimWithin(100, TimeUnit.MILLISECONDS);
        } finally {
            polling = false;
        }
    }

    private void deliverBatch(LogEvent first) {
        activeDeliveries.incrementAndGet();
        boolean interrupted = false;
        try {
            interrupted = batchDelivery.deliver(first, this::claimBatchFollower);
        } finally {
            activeDeliveries.decrementAndGet();
            if (interrupted && running) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private LogEvent claimBatchFollower(long timeoutNanos) throws InterruptedException {
        polling = true;
        try {
            return eventQueue.claimWithin(timeoutNanos, TimeUnit.NANOSECONDS);
        } finally {
            polling = false;
        }
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
