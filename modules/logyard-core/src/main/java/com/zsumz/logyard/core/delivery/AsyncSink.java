package com.zsumz.logyard.core.delivery;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.BatchEventSink;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.api.spi.HealthContributor;
import com.zsumz.logyard.core.diagnostics.EmergencyText;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded asynchronous delivery with explicit per-severity overload behavior. */
public final class AsyncSink implements EventSink, HealthContributor {
    public static final int MAX_CAPACITY = 16_777_216;
    private final String name;
    private final BatchEventSink batchDelegate;
    private final int maximumBatchSize;
    private final long maximumBatchDelayNanos;
    private final boolean callerThreadDeliveryAllowed;
    private final AsyncEventQueue eventQueue;
    private final OverflowPolicy overflowPolicy;
    private final Duration shutdownTimeout;
    private final AsyncSinkMetrics metrics = new AsyncSinkMetrics();
    private final AsyncDropReporter dropReporter = new AsyncDropReporter(metrics);
    private final AsyncSinkDiagnostics diagnostics;
    private final AsyncDelegateDelivery delivery;
    private final Thread worker;
    private final AtomicInteger activeDeliveries = new AtomicInteger();
    private final AtomicBoolean delegateCloseStarted = new AtomicBoolean();
    private volatile boolean accepting = true;
    private volatile boolean running = true;
    private volatile boolean closeDelegateOnExit;
    private volatile boolean workerPolling;

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
            throw new IllegalArgumentException(
                    "async capacity must be between 16 and " + MAX_CAPACITY);
        }
        this.name = CaptureLimits.name(Objects.requireNonNull(name, "name"));
        diagnostics = new AsyncSinkDiagnostics(this.name);
        delivery = new AsyncDelegateDelivery(Objects.requireNonNull(delegate, "delegate"), metrics, diagnostics);
        batchDelegate = delivery.batchDelegate();
        maximumBatchSize = batchDelegate == null ? 1 : batchDelegate.maximumBatchSize();
        if (maximumBatchSize < 1 || maximumBatchSize > 4_096) {
            throw new IllegalArgumentException("batch size must be between 1 and 4096");
        }
        Duration batchDelay = batchDelegate == null ? Duration.ZERO : batchDelegate.maximumBatchDelay();
        Objects.requireNonNull(batchDelay, "maximumBatchDelay");
        if (batchDelay.isNegative() || batchDelay.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("batch delay must be between 0s and 1m");
        }
        maximumBatchDelayNanos = saturatedNanos(batchDelay);
        this.callerThreadDeliveryAllowed = callerThreadDeliveryAllowed;
        eventQueue = new AsyncEventQueue(capacity);
        this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy");
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdown timeout must not be negative");
        }
        worker = new Thread(this::drainLoop, "logyard-output-"
                + EmergencyText.threadComponent(this.name, 64));
        worker.setDaemon(true);
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
            case SYNC -> {
                if (!rule.waitDuration().isZero() && offerWithWait(event, rule.waitDuration())) {
                    return;
                }
                if (callerThreadDeliveryAllowed) {
                    metrics.recordSynchronousFallback();
                    deliverApplicationEvent(event, false);
                } else {
                    metrics.recordEmergencyFallback();
                    diagnostics.emergency(event, "caller-thread delivery is forbidden for this output");
                }
            }
            case STDERR -> {
                if (!rule.waitDuration().isZero() && offerWithWait(event, rule.waitDuration())) {
                    return;
                }
                metrics.recordEmergencyFallback();
                diagnostics.emergency(event, "async queue full");
            }
            case BLOCK -> block(event, rule.waitDuration());
        }
    }

    @Override
    public void flush() {
        if (!eventQueue.awaitQuiescence(shutdownTimeout)) {
            diagnostics.status("flush deadline elapsed with " + eventQueue.queued()
                    + " queued and " + activeDeliveries.get() + " active event(s)");
            return;
        }
        delivery.flush();
    }

    @Override
    public synchronized void close() {
        if (delegateCloseStarted.get()) {
            return;
        }
        accepting = false;
        running = false;
        closeDelegateOnExit = true;
        if (workerPolling) {
            worker.interrupt();
        }
        if (!awaitWorker(shutdownTimeout)) {
            drainQueueToEmergency("shutdown deadline elapsed");
            diagnostics.status("worker did not stop within " + shutdownTimeout
                    + "; daemon cleanup will close the delegate when delivery exits");
            return;
        }
        closeDelegate();
    }

    public int capacity() { return eventQueue.capacity(); }
    public int queued() { return eventQueue.queued(); }
    public long dropped(Level level) { return metrics.dropped(level); }
    public long queuedEvents() { return metrics.enqueued(); }
    public long deliveredEvents() { return metrics.delivered(); }
    public long synchronousFallbacks() { return metrics.synchronousFallbacks(); }
    public long emergencyFallbacks() { return metrics.emergencyFallbacks(); }

    @Override
    public ComponentHealth health(String componentName) {
        return AsyncSinkHealth.snapshot(componentName, delivery.delegate(), new AsyncSinkHealth.State(
                running,
                accepting,
                worker.isAlive(),
                delegateCloseStarted.get(),
                callerThreadDeliveryAllowed,
                batchDelegate != null,
                capacity(),
                queued(),
                activeDeliveries.get(),
                eventQueue.outstanding(),
                maximumBatchSize,
                metrics.snapshot()));
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

    private void drainLoop() {
        try {
            while (running || !eventQueue.isEmpty()) {
                try {
                    workerPolling = true;
                    LogEvent event;
                    try {
                        event = eventQueue.claimWithin(100, TimeUnit.MILLISECONDS);
                    } finally {
                        workerPolling = false;
                    }
                    if (event != null) {
                        if (batchDelegate == null) {
                            deliverApplicationEvent(event, true);
                        } else {
                            deliverBatch(event);
                        }
                    }
                    emitDropSummary(false);
                } catch (InterruptedException ignored) {
                    // close() interrupts only the queue poll to begin draining immediately.
                } catch (RuntimeException failure) {
                    diagnostics.status("output failure: "
                            + EmergencyText.failureSummary(failure, 2_048));
                }
            }
        } finally {
            if (closeDelegateOnExit) {
                try {
                    closeDelegate();
                } catch (RuntimeException failure) {
                    diagnostics.status("delegate close failure: "
                            + EmergencyText.failureSummary(failure, 2_048));
                }
            }
        }
    }

    private void deliverBatch(LogEvent first) {
        activeDeliveries.incrementAndGet();
        boolean interrupted = false;
        List<LogEvent> events = new ArrayList<>(maximumBatchSize);
        events.add(first);
        try {
            long deadline = maximumBatchDelayNanos == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : saturatedAdd(System.nanoTime(), maximumBatchDelayNanos);
            while (events.size() < maximumBatchSize) {
                LogEvent next;
                if (maximumBatchDelayNanos == 0L) {
                    next = eventQueue.claimNow();
                } else {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        break;
                    }
                    workerPolling = true;
                    try {
                        next = eventQueue.claimWithin(remaining, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                        break;
                    } finally {
                        workerPolling = false;
                    }
                }
                if (next == null) {
                    break;
                }
                events.add(next);
            }
            delivery.deliverBatch(events);
        } finally {
            eventQueue.completeClaims(events.size());
            activeDeliveries.decrementAndGet();
            if (interrupted && running) {
                Thread.currentThread().interrupt();
            }
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

    private void deliverInternalEvent(LogEvent event) {
        delivery.deliverInternalEvent(event);
    }

    private void emitDropSummary(boolean force) {
        LogEvent report = dropReporter.nextReport(force);
        if (report != null) {
            deliverInternalEvent(report);
        }
    }

    private boolean awaitWorker(Duration timeout) {
        if (!worker.isAlive()) {
            return true;
        }
        long timeoutNanos = saturatedNanos(timeout);
        if (timeoutNanos == 0) {
            return false;
        }
        try {
            worker.join(timeoutNanos / 1_000_000L, (int) (timeoutNanos % 1_000_000L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !worker.isAlive();
    }

    private void drainQueueToEmergency(String reason) {
        LogEvent remaining;
        while ((remaining = eventQueue.claimNow()) != null) {
            eventQueue.completeClaims(1);
            metrics.recordEmergencyFallback();
            diagnostics.emergency(remaining, reason);
        }
    }

    private void closeDelegate() {
        if (!delegateCloseStarted.compareAndSet(false, true)) {
            return;
        }
        emitDropSummary(true);
        delivery.close(name);
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        return result < left ? Long.MAX_VALUE : result;
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

}
