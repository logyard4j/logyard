package com.zsumz.logyard.core.delivery;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.api.spi.HealthContributor;

import java.time.Duration;
import java.util.Objects;

/** Bounded asynchronous admission with explicit per-severity overload behavior. */
public final class AsyncSink implements EventSink, HealthContributor {
    public static final int MAX_CAPACITY = 16_777_216;

    private final boolean callerThreadDeliveryAllowed;
    private final AsyncEventQueue eventQueue;
    private final OverflowPolicy overflowPolicy;
    private final Duration shutdownTimeout;
    private final AsyncSinkMetrics metrics = new AsyncSinkMetrics();
    private final AsyncSinkDiagnostics diagnostics;
    private final AsyncSinkWorker worker;
    private volatile boolean accepting = true;

    public AsyncSink(
            String name,
            EventSink delegate,
            int capacity,
            OverflowPolicy overflowPolicy,
            Duration shutdownTimeout) {
        this(name, delegate, capacity, overflowPolicy, shutdownTimeout, true);
    }

    public AsyncSink(
            String name,
            EventSink delegate,
            int capacity,
            OverflowPolicy overflowPolicy,
            Duration shutdownTimeout,
            boolean callerThreadDeliveryAllowed) {
        if (capacity < 16 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("async capacity must be between 16 and " + MAX_CAPACITY);
        }
        String normalizedName = CaptureLimits.name(Objects.requireNonNull(name, "name"));
        this.callerThreadDeliveryAllowed = callerThreadDeliveryAllowed;
        eventQueue = new AsyncEventQueue(capacity);
        this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy");
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdown timeout must not be negative");
        }
        diagnostics = new AsyncSinkDiagnostics(normalizedName);
        worker = new AsyncSinkWorker(
                normalizedName,
                Objects.requireNonNull(delegate, "delegate"),
                eventQueue,
                metrics,
                diagnostics);
        worker.start();
    }

    @Override
    public void accept(LogEvent event) {
        Objects.requireNonNull(event, "event");
        if (!accepting) {
            metrics.recordEmergencyFallback();
            diagnostics.emergency(event, "output is closing");
            return;
        }
        if (offerImmediately(event)) {
            return;
        }

        OverflowPolicy.Rule rule = overflowPolicy.ruleFor(event.level());
        switch (rule.action()) {
            case DROP -> metrics.recordDrop(event.level());
            case SYNC -> synchronizeOrReport(event, rule.waitDuration());
            case STDERR -> enqueueOrReport(event, rule.waitDuration());
            case BLOCK -> block(event, rule.waitDuration());
        }
    }

    @Override
    public void flush() {
        worker.flush(shutdownTimeout);
    }

    @Override
    public synchronized void close() {
        accepting = false;
        worker.close(shutdownTimeout);
    }

    public int capacity() {
        return eventQueue.capacity();
    }

    public int queued() {
        return eventQueue.queued();
    }

    public long dropped(Level level) {
        return metrics.dropped(level);
    }

    public long queuedEvents() {
        return metrics.enqueued();
    }

    public long deliveredEvents() {
        return metrics.delivered();
    }

    public long synchronousFallbacks() {
        return metrics.synchronousFallbacks();
    }

    public long emergencyFallbacks() {
        return metrics.emergencyFallbacks();
    }

    @Override
    public ComponentHealth health(String componentName) {
        return AsyncSinkHealth.snapshot(
                componentName,
                worker.delegate(),
                worker.healthState(accepting, callerThreadDeliveryAllowed, capacity(), queued(), eventQueue.outstanding(), metrics.snapshot()));
    }

    private void synchronizeOrReport(LogEvent event, Duration wait) {
        if (!wait.isZero() && offerWithWait(event, wait)) {
            return;
        }
        if (callerThreadDeliveryAllowed) {
            metrics.recordSynchronousFallback();
            worker.deliverOnCallerThread(event);
        } else {
            metrics.recordEmergencyFallback();
            diagnostics.emergency(event, "caller-thread delivery is forbidden for this output");
        }
    }

    private void enqueueOrReport(LogEvent event, Duration wait) {
        if (!wait.isZero() && offerWithWait(event, wait)) {
            return;
        }
        metrics.recordEmergencyFallback();
        diagnostics.emergency(event, "async queue full");
    }

    private boolean offerImmediately(LogEvent event) {
        return handleOffer(event, eventQueue.offerImmediately(event, () -> accepting));
    }

    private void block(LogEvent event, Duration wait) {
        if (wait.isZero()) {
            metrics.recordEmergencyFallback();
            diagnostics.emergency(event, "async queue full with zero block timeout");
            return;
        }
        try {
            if (!handleOffer(event, eventQueue.offerWithin(event, wait, () -> accepting))) {
                metrics.recordEmergencyFallback();
                diagnostics.emergency(event, "async queue full after block timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            metrics.recordEmergencyFallback();
            diagnostics.emergency(event, "interrupted while waiting for logging queue");
        }
    }

    private boolean offerWithWait(LogEvent event, Duration wait) {
        try {
            return handleOffer(event, eventQueue.offerWithin(event, wait, () -> accepting));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean handleOffer(LogEvent event, AsyncEventQueue.OfferResult result) {
        if (result == AsyncEventQueue.OfferResult.FULL) {
            return false;
        }
        if (result == AsyncEventQueue.OfferResult.CLOSED) {
            metrics.recordEmergencyFallback();
            diagnostics.emergency(event, "output closed while the event was being enqueued");
        } else {
            metrics.recordEnqueued();
        }
        return true;
    }
}
