package com.logyard4j.core.delivery.async;

import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.output.BatchEventSink;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.diagnostics.EmergencyText;
import com.logyard4j.core.failure.ComponentInvocationBoundary;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes every interaction with one asynchronous output delegate. */
final class AsyncDelegateDelivery {
    private static final int MAX_EMERGENCY_BATCH_EVENTS = 16;

    private final EventSink delegate;
    private final BatchEventSink batchDelegate;
    private final AsyncSinkMetrics metrics;
    private final AsyncSinkDiagnostics diagnostics;
    private final ReentrantLock lock = new ReentrantLock();

    AsyncDelegateDelivery(EventSink delegate, AsyncSinkMetrics metrics, AsyncSinkDiagnostics diagnostics) {
        this.delegate = delegate;
        batchDelegate = delegate instanceof BatchEventSink batch ? batch : null;
        this.metrics = metrics;
        this.diagnostics = diagnostics;
    }

    EventSink delegate() {
        return delegate;
    }

    BatchEventSink batchDelegate() {
        return batchDelegate;
    }

    void deliverEvent(LogEvent event) {
        lock.lock();
        try {
            if (ComponentInvocationBoundary.invoke(
                    "async output delegate accept",
                    () -> delegate.accept(event),
                    (component, failure) -> {
                        metrics.recordEmergencyFallback();
                        diagnostics.emergency(event, componentFailure(component, failure));
                        diagnostics.failure(component, failure);
                    })) {
                metrics.recordDelivered();
            }
        } finally {
            lock.unlock();
        }
    }

    void deliverBatch(List<LogEvent> events) {
        lock.lock();
        try {
            if (ComponentInvocationBoundary.invoke(
                    "async output batch delegate accept",
                    () -> batchDelegate.acceptBatch(List.copyOf(events)),
                    (component, failure) -> {
                        metrics.recordEmergencyFallbacks(events.size());
                        int reported = Math.min(events.size(), MAX_EMERGENCY_BATCH_EVENTS);
                        for (int index = 0; index < reported; index++) {
                            diagnostics.emergency(events.get(index), componentFailure(component, failure));
                        }
                        if (events.size() > reported) {
                            diagnostics.status((events.size() - reported) + " additional event(s) omitted from emergency output");
                        }
                        diagnostics.failure(component, failure);
                    })) {
                metrics.recordDelivered(events.size());
            }
        } finally {
            lock.unlock();
        }
    }

    void deliverInternalEvent(LogEvent event) {
        lock.lock();
        try {
            ComponentInvocationBoundary.invoke(
                    "async output internal-event accept",
                    () -> delegate.accept(event),
                    diagnostics::failure);
        } finally {
            lock.unlock();
        }
    }

    void flush() {
        lock.lock();
        try {
            ComponentInvocationBoundary.invoke(
                    "async output delegate flush",
                    delegate::flush,
                    diagnostics::failure);
        } finally {
            lock.unlock();
        }
    }

    void close(String outputName) {
        lock.lock();
        try {
            ComponentInvocationBoundary.invoke(
                    "async output '" + outputName + "' delegate flush during close",
                    delegate::flush,
                    diagnostics::failure);
            ComponentInvocationBoundary.invoke(
                    "async output '" + outputName + "' delegate close",
                    delegate::close,
                    diagnostics::failure);
        } finally {
            lock.unlock();
        }
    }

    private static String componentFailure(String component, Throwable failure) {
        return component + " failure: " + EmergencyText.failureSummary(failure, 512);
    }
}
