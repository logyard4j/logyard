package com.logyard4j.logyard.core.delivery.async;

import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.output.BatchEventSink;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.diagnostics.EmergencyText;
import com.logyard4j.logyard.core.failure.ComponentInvocationBoundary;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes every interaction with one asynchronous output delegate. */
final class AsyncDelegateDelivery {
    private static final int MAX_EMERGENCY_BATCH_EVENTS = 16;

    private final EventSink delegate;
    private final BatchEventSink batchDelegate;
    private final AsyncSinkMetrics metrics;
    private final AsyncSinkDiagnostics diagnostics;
    private final OverflowPolicy overflowPolicy;
    private final ReentrantLock lock;
    private Phase phase = Phase.OPEN;

    AsyncDelegateDelivery(EventSink delegate, AsyncSinkMetrics metrics, AsyncSinkDiagnostics diagnostics) {
        this(delegate, metrics, diagnostics, new OverflowPolicy(null));
    }

    AsyncDelegateDelivery(EventSink delegate, AsyncSinkMetrics metrics, AsyncSinkDiagnostics diagnostics, OverflowPolicy overflowPolicy) {
        this(delegate, metrics, diagnostics, overflowPolicy, new ReentrantLock());
    }

    AsyncDelegateDelivery(EventSink delegate, AsyncSinkMetrics metrics, AsyncSinkDiagnostics diagnostics,
            OverflowPolicy overflowPolicy, ReentrantLock lock) {
        this.delegate = delegate;
        batchDelegate = delegate instanceof BatchEventSink batch ? batch : null;
        this.metrics = metrics;
        this.diagnostics = diagnostics;
        this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy");
        this.lock = Objects.requireNonNull(lock, "lock");
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
            if (phase != Phase.OPEN) {
                rejectClosed(List.of(event));
                return;
            }
            try {
                delegate.accept(event);
            } catch (Throwable failure) {
                ComponentInvocationBoundary.report("async output delegate accept", failure,
                    (component, current) -> {
                        metrics.recordEmergencyFallback();
                        diagnostics.emergency(event, componentFailure(component, current));
                        diagnostics.failure(component, current);
                    });
                return;
            }
            metrics.recordDelivered();
        } finally {
            lock.unlock();
        }
    }

    void deliverBatch(List<LogEvent> events) {
        lock.lock();
        try {
            if (phase != Phase.OPEN) {
                rejectClosed(events);
                return;
            }
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
            if (phase != Phase.OPEN) {
                return;
            }
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
            if (phase != Phase.OPEN) {
                return;
            }
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
            if (phase != Phase.OPEN) {
                return;
            }
            phase = Phase.CLOSING;
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
                phase = Phase.CLOSED;
            }
        } finally {
            lock.unlock();
        }
    }

    private void rejectClosed(List<LogEvent> events) {
        int emergencyEvents = 0;
        for (LogEvent event : events) {
            if (overflowPolicy.dropsUndelivered(event.level())) {
                metrics.recordDrop(event.level());
            } else {
                metrics.recordEmergencyFallback();
                if (emergencyEvents++ < MAX_EMERGENCY_BATCH_EVENTS) {
                    diagnostics.emergency(event, "output closed before delegate delivery");
                }
            }
        }
        if (emergencyEvents > MAX_EMERGENCY_BATCH_EVENTS) {
            diagnostics.status((emergencyEvents - MAX_EMERGENCY_BATCH_EVENTS) + " additional event(s) omitted from emergency output");
        }
    }

    private static String componentFailure(String component, Throwable failure) {
        return component + " failure: " + EmergencyText.failureSummary(failure, 512);
    }

    private enum Phase {
        OPEN,
        CLOSING,
        CLOSED
    }
}
