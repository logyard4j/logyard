package com.zsumz.logyard.core.delivery.async;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.failure.ComponentInvocationBoundary;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the asynchronous worker thread, bounded shutdown, and final delegate closure. */
final class AsyncSinkWorker {
    private final String name;
    private final AsyncEventQueue eventQueue;
    private final AsyncSinkDiagnostics diagnostics;
    private final AsyncDelegateDelivery delivery;
    private final AsyncDeliveryLoop deliveryLoop;
    private final AsyncWorkerThread worker;
    private final AtomicBoolean delegateCloseStarted = new AtomicBoolean();
    private volatile boolean closeDelegateOnExit;

    AsyncSinkWorker(
            String name,
            EventSink delegate,
            AsyncEventQueue eventQueue,
            AsyncSinkMetrics metrics,
            AsyncSinkDiagnostics diagnostics) {
        this.name = name;
        this.eventQueue = eventQueue;
        this.diagnostics = diagnostics;
        delivery = new AsyncDelegateDelivery(delegate, metrics, diagnostics);
        deliveryLoop = new AsyncDeliveryLoop(name, eventQueue, metrics, diagnostics, delivery);
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

    void flush(Duration timeout) {
        if (!deliveryLoop.awaitQuiescence(timeout)) {
            diagnostics.status("flush deadline elapsed with " + eventQueue.queued() + " queued and "
                    + deliveryLoop.activeDeliveries() + " active event(s)");
            return;
        }
        delivery.flush();
    }

    synchronized void close(Duration timeout) {
        if (delegateCloseStarted.get()) {
            return;
        }
        deliveryLoop.stop();
        closeDelegateOnExit = true;
        if (deliveryLoop.polling()) {
            worker.interrupt();
        }
        if (!worker.await(timeout)) {
            deliveryLoop.drainQueueToEmergency("shutdown deadline elapsed");
            diagnostics.status("worker did not stop within " + timeout + "; daemon cleanup will close the delegate when delivery exits");
            return;
        }
        closeDelegate();
    }

    AsyncSinkHealth.State healthState(
            boolean accepting,
            boolean callerThreadDeliveryAllowed,
            int capacity,
            int queued,
            int outstandingQueuedEvents,
            AsyncSinkMetrics.Snapshot telemetry) {
        return new AsyncSinkHealth.State(
                deliveryLoop.running(),
                accepting,
                worker.alive(),
                delegateCloseStarted.get(),
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
            if (closeDelegateOnExit) {
                ComponentInvocationBoundary.invoke(
                        "async output worker delegate close",
                        this::closeDelegate,
                        diagnostics::failure);
            }
        }
    }

    private void closeDelegate() {
        if (!delegateCloseStarted.compareAndSet(false, true)) {
            return;
        }
        deliveryLoop.closeDelegate(name);
    }
}
