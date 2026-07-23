package com.zsumz.logyard.core.delivery;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.BatchEventSink;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.core.diagnostics.EmergencyText;

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
            delegate.accept(event);
            metrics.recordDelivered();
        } catch (RuntimeException failure) {
            metrics.recordEmergencyFallback();
            diagnostics.emergency(event, "delegate failure: " + failure.getClass().getSimpleName());
            diagnostics.status("delegate accept failure: " + EmergencyText.failureSummary(failure, 2_048));
        } finally {
            lock.unlock();
        }
    }

    void deliverBatch(List<LogEvent> events) {
        lock.lock();
        try {
            batchDelegate.acceptBatch(List.copyOf(events));
            metrics.recordDelivered(events.size());
        } catch (RuntimeException failure) {
            metrics.recordEmergencyFallbacks(events.size());
            int reported = Math.min(events.size(), MAX_EMERGENCY_BATCH_EVENTS);
            for (int index = 0; index < reported; index++) {
                diagnostics.emergency(events.get(index), "batch delegate failure: " + failure.getClass().getSimpleName());
            }
            if (events.size() > reported) {
                diagnostics.status((events.size() - reported) + " additional event(s) omitted from emergency output");
            }
            diagnostics.status("batch delegate failure: " + EmergencyText.failureSummary(failure, 2_048));
        } finally {
            lock.unlock();
        }
    }

    void deliverInternalEvent(LogEvent event) {
        lock.lock();
        try {
            delegate.accept(event);
        } catch (RuntimeException failure) {
            diagnostics.status("failed to report dropped events: " + EmergencyText.failureSummary(failure, 2_048));
        } finally {
            lock.unlock();
        }
    }

    void flush() {
        lock.lock();
        try {
            delegate.flush();
        } catch (RuntimeException failure) {
            diagnostics.status("delegate flush failure: " + EmergencyText.failureSummary(failure, 2_048));
        } finally {
            lock.unlock();
        }
    }

    void close(String outputName) {
        RuntimeException failure = null;
        lock.lock();
        try {
            try {
                delegate.flush();
            } catch (RuntimeException current) {
                failure = current;
            }
            try {
                delegate.close();
            } catch (RuntimeException current) {
                if (failure == null) {
                    failure = current;
                } else {
                    failure.addSuppressed(current);
                }
            }
        } finally {
            lock.unlock();
        }
        if (failure != null) {
            throw new IllegalStateException("failed to close Logyard async output '" + outputName + "'", failure);
        }
    }
}
