package com.zsumz.logyard.core.delivery.async;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.diagnostics.EmergencyText;
import com.zsumz.logyard.core.failure.ComponentInvocationBoundary;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns the asynchronous drain thread, batch formation, delegate delivery, and shutdown lifecycle. */
final class AsyncSinkWorker {
    private final String name;
    private final AsyncEventQueue eventQueue;
    private final AsyncSinkMetrics metrics;
    private final AsyncDropReporter dropReporter;
    private final AsyncSinkDiagnostics diagnostics;
    private final AsyncDelegateDelivery delivery;
    private final AsyncBatchPolicy batching;
    private final Thread thread;
    private final AtomicInteger activeDeliveries = new AtomicInteger();
    private final AtomicBoolean delegateCloseStarted = new AtomicBoolean();
    private volatile boolean running = true;
    private volatile boolean closeDelegateOnExit;
    private volatile boolean polling;

    AsyncSinkWorker(
            String name,
            EventSink delegate,
            AsyncEventQueue eventQueue,
            AsyncSinkMetrics metrics,
            AsyncSinkDiagnostics diagnostics) {
        this.name = name;
        this.eventQueue = eventQueue;
        this.metrics = metrics;
        this.diagnostics = diagnostics;
        dropReporter = new AsyncDropReporter(metrics);
        delivery = new AsyncDelegateDelivery(delegate, metrics, diagnostics);
        batching = AsyncBatchPolicy.from(name, delivery);
        thread = new Thread(this::drainLoop, "logyard-output-" + EmergencyText.threadComponent(name, 64));
        thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    EventSink delegate() {
        return delivery.delegate();
    }

    void deliverOnCallerThread(LogEvent event) {
        deliverApplicationEvent(event, false);
    }

    void flush(Duration timeout) {
        if (!eventQueue.awaitQuiescence(timeout)) {
            diagnostics.status("flush deadline elapsed with " + eventQueue.queued() + " queued and " + activeDeliveries.get() + " active event(s)");
            return;
        }
        delivery.flush();
    }

    synchronized void close(Duration timeout) {
        if (delegateCloseStarted.get()) {
            return;
        }
        running = false;
        closeDelegateOnExit = true;
        if (polling) {
            thread.interrupt();
        }
        if (!awaitThread(timeout)) {
            drainQueueToEmergency("shutdown deadline elapsed");
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
                running,
                accepting,
                thread.isAlive(),
                delegateCloseStarted.get(),
                callerThreadDeliveryAllowed,
                batching.enabled(),
                capacity,
                queued,
                activeDeliveries.get(),
                outstandingQueuedEvents,
                batching.maximumSize(),
                telemetry);
    }

    private void drainLoop() {
        try {
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
                    // close() interrupts only the queue poll to begin draining immediately.
                } catch (Throwable failure) {
                    ComponentInvocationBoundary.report(
                            "async output worker",
                            failure,
                            diagnostics::failure);
                }
            }
        } finally {
            if (closeDelegateOnExit) {
                ComponentInvocationBoundary.invoke(
                        "async output worker delegate close",
                        this::closeDelegate,
                        diagnostics::failure);
            }
        }
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
        List<LogEvent> events = new ArrayList<>(batching.maximumSize());
        events.add(first);
        try {
            long deadline = batching.maximumDelayNanos() == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : saturatedAdd(System.nanoTime(), batching.maximumDelayNanos());
            while (events.size() < batching.maximumSize()) {
                LogEvent next;
                if (batching.maximumDelayNanos() == 0L) {
                    next = eventQueue.claimNow();
                } else {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        break;
                    }
                    polling = true;
                    try {
                        next = eventQueue.claimWithin(remaining, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                        break;
                    } finally {
                        polling = false;
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

    private void emitDropSummary(boolean force) {
        LogEvent report = dropReporter.nextReport(force);
        if (report != null) {
            delivery.deliverInternalEvent(report);
        }
    }

    private boolean awaitThread(Duration timeout) {
        if (!thread.isAlive()) {
            return true;
        }
        long timeoutNanos = saturatedNanos(timeout);
        if (timeoutNanos == 0) {
            return false;
        }
        try {
            thread.join(timeoutNanos / 1_000_000L, (int) (timeoutNanos % 1_000_000L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !thread.isAlive();
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
