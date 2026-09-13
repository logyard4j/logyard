package com.logyard4j.core.delivery.async;

import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.failure.ComponentInvocationBoundary;

import java.time.Duration;

/** Owns the asynchronous worker thread, bounded shutdown, and final delegate closure. */
final class AsyncSinkWorker {
    private final String name;
    private final AsyncEventQueue eventQueue;
    private final AsyncSinkDiagnostics diagnostics;
    private final AsyncDelegateDelivery delivery;
    private final AsyncWorkerLifecycle lifecycle;
    private final AsyncDeliveryLoop deliveryLoop;
    private final AsyncWorkerThread worker;
    private final OverflowPolicy overflowPolicy;

    AsyncSinkWorker(
            String name,
            EventSink delegate,
            AsyncEventQueue eventQueue,
            AsyncSinkMetrics metrics,
            AsyncSinkDiagnostics diagnostics,
            OverflowPolicy overflowPolicy) {
        this.name = name;
        this.eventQueue = eventQueue;
        this.diagnostics = diagnostics;
        this.overflowPolicy = overflowPolicy;
        delivery = new AsyncDelegateDelivery(delegate, metrics, diagnostics, overflowPolicy);
        lifecycle = new AsyncWorkerLifecycle();
        deliveryLoop = new AsyncDeliveryLoop(name, eventQueue, metrics, diagnostics, delivery, lifecycle);
        worker = new AsyncWorkerThread(name, this::drainLoop);
    }

    void start() {
        worker.start();
    }

    EventSink delegate() {
        return delivery.delegate();
    }

    void deliverOnCallerThread(LogEvent event) {
        deliveryLoop.deliverOnCallerThread(event);
    }

    boolean acceptingEvents() {
        return lifecycle.acceptingEvents();
    }

    void flush(Duration timeout) {
        if (!deliveryLoop.awaitQuiescence(timeout)) {
            diagnostics.status("flush deadline elapsed with " + eventQueue.queued() + " queued and "
                    + deliveryLoop.activeDeliveries() + " active event(s)");
            return;
        }
        delivery.flush();
    }

    synchronized void close(Duration timeout) {
        AsyncWorkerLifecycle.DrainRequest drainRequest = lifecycle.requestDrain();
        if (drainRequest == AsyncWorkerLifecycle.DrainRequest.COMPLETE) {
            return;
        }
        if (drainRequest == AsyncWorkerLifecycle.DrainRequest.STARTED) {
            eventQueue.wakeWorker();
        }
        if (!worker.await(timeout)) {
            deliveryLoop.drainQueueToEmergency("shutdown deadline elapsed", overflowPolicy);
            diagnostics.status("worker did not stop within " + timeout + "; daemon cleanup will close the delegate when delivery exits");
        }
    }

    AsyncSinkHealth.State healthState(
            boolean callerThreadDeliveryAllowed,
            int capacity,
            int queued,
            int outstandingQueuedEvents,
            AsyncSinkMetrics.Snapshot telemetry) {
        return new AsyncSinkHealth.State(
                lifecycle.workerRunning(),
                lifecycle.acceptingEvents(),
                worker.alive(),
                lifecycle.delegateCloseStarted(),
                callerThreadDeliveryAllowed,
                deliveryLoop.batchingEnabled(),
                capacity,
                queued,
                deliveryLoop.activeDeliveries(),
                outstandingQueuedEvents,
                deliveryLoop.maximumBatchSize(),
                telemetry);
    }

    private void drainLoop() {
        try {
            deliveryLoop.run();
        } finally {
            if (lifecycle.beginDelegateClose()) {
                try {
                    ComponentInvocationBoundary.invoke(
                            "async output worker delegate close",
                            () -> deliveryLoop.closeDelegate(name),
                            diagnostics::failure);
                } finally {
                    lifecycle.completeDelegateClose();
                }
            }
        }
    }
}
