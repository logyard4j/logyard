package com.zsumz.logyard.core.delivery;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.diagnostics.HealthStatus;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.ExceptionSnapshot;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.BatchEventSink;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.api.spi.HealthContributor;
import com.zsumz.logyard.core.diagnostics.EmergencyText;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded asynchronous delivery with explicit per-severity overload behavior. */
public final class AsyncSink implements EventSink, HealthContributor {
    public static final int MAX_CAPACITY = 16_777_216;
    private final String name;
    private final EventSink delegate;
    private final BatchEventSink batchDelegate;
    private final int maximumBatchSize;
    private final long maximumBatchDelayNanos;
    private final boolean callerThreadDeliveryAllowed;
    private final ArrayBlockingQueue<LogEvent> queue;
    private final OverflowPolicy overflowPolicy;
    private final Duration shutdownTimeout;
    private final Thread worker;
    private final ReentrantLock deliveryLock = new ReentrantLock();
    private final AtomicInteger activeDeliveries = new AtomicInteger();
    private final AtomicInteger outstandingQueuedEvents = new AtomicInteger();
    private final AtomicBoolean delegateCloseStarted = new AtomicBoolean();
    private final EnumMap<Level, LongAdder> dropped = new EnumMap<>(Level.class);
    private final EnumMap<Level, LongAdder> pendingDropReports = new EnumMap<>(Level.class);
    private final LongAdder queuedEvents = new LongAdder();
    private final LongAdder deliveredEvents = new LongAdder();
    private final LongAdder synchronousFallbacks = new LongAdder();
    private final LongAdder emergencyFallbacks = new LongAdder();
    private volatile boolean accepting = true;
    private volatile boolean running = true;
    private volatile boolean closeDelegateOnExit;
    private volatile boolean workerPolling;
    private volatile long nextDropReportNanos;

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
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        batchDelegate = delegate instanceof BatchEventSink batch ? batch : null;
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
        queue = new ArrayBlockingQueue<>(capacity);
        this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy");
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdown timeout must not be negative");
        }
        for (Level level : Level.values()) {
            dropped.put(level, new LongAdder());
            pendingDropReports.put(level, new LongAdder());
        }
        nextDropReportNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        worker = new Thread(this::drainLoop, "logyard-output-"
                + EmergencyText.threadComponent(this.name, 64));
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void accept(LogEvent event) {
        Objects.requireNonNull(event, "event");
        if (!accepting) {
            emergencyFallbacks.increment();
            emergency(event, "output is closing");
            return;
        }
        if (offerImmediately(event)) {
            return;
        }
        OverflowPolicy.Rule rule = overflowPolicy.ruleFor(event.level());
        switch (rule.action()) {
            case DROP -> recordDrop(event.level());
            case SYNC -> {
                if (!rule.waitDuration().isZero() && offerWithWait(event, rule.waitDuration())) {
                    return;
                }
                if (callerThreadDeliveryAllowed) {
                    synchronousFallbacks.increment();
                    deliverApplicationEvent(event, false);
                } else {
                    emergencyFallbacks.increment();
                    emergency(event, "caller-thread delivery is forbidden for this output");
                }
            }
            case STDERR -> {
                if (!rule.waitDuration().isZero() && offerWithWait(event, rule.waitDuration())) {
                    return;
                }
                emergencyFallbacks.increment();
                emergency(event, "async queue full");
            }
            case BLOCK -> block(event, rule.waitDuration());
        }
    }

    @Override
    public void flush() {
        if (!awaitQuiescence(shutdownTimeout)) {
            internalStatus("flush deadline elapsed with " + queue.size()
                    + " queued and " + activeDeliveries.get() + " active event(s)");
            return;
        }
        deliveryLock.lock();
        try {
            delegate.flush();
        } catch (RuntimeException failure) {
            internalStatus("delegate flush failure: "
                    + EmergencyText.failureSummary(failure, 2_048));
        } finally {
            deliveryLock.unlock();
        }
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
            internalStatus("worker did not stop within " + shutdownTimeout
                    + "; daemon cleanup will close the delegate when delivery exits");
            return;
        }
        closeDelegate();
    }

    public int capacity() { return queue.size() + queue.remainingCapacity(); }
    public int queued() { return queue.size(); }
    public long dropped(Level level) { return dropped.get(level).sum(); }
    public long queuedEvents() { return queuedEvents.sum(); }
    public long deliveredEvents() { return deliveredEvents.sum(); }
    public long synchronousFallbacks() { return synchronousFallbacks.sum(); }
    public long emergencyFallbacks() { return emergencyFallbacks.sum(); }

    @Override
    public ComponentHealth health(String componentName) {
        int currentQueued = queued();
        int currentCapacity = capacity();
        long droppedTotal = 0L;
        for (Level level : Level.values()) {
            droppedTotal += dropped(level);
        }
        long emergency = emergencyFallbacks();
        long synchronous = synchronousFallbacks();
        HealthStatus status;
        if (!worker.isAlive() && running) {
            status = HealthStatus.FAILED;
        } else if (delegateCloseStarted.get()) {
            status = worker.isAlive() ? HealthStatus.STOPPING : HealthStatus.STOPPED;
        } else if (!accepting) {
            status = HealthStatus.STOPPING;
        } else if (droppedTotal > 0L || emergency > 0L
                || currentQueued * 5L >= currentCapacity * 4L) {
            status = HealthStatus.DEGRADED;
        } else {
            status = HealthStatus.HEALTHY;
        }

        Map<String, String> details = new LinkedHashMap<>();
        details.put("delivery", "async");
        details.put("accepting", Boolean.toString(accepting));
        details.put("worker_alive", Boolean.toString(worker.isAlive()));
        details.put("delegate", delegate.getClass().getName());
        details.put("caller_thread_delivery", Boolean.toString(callerThreadDeliveryAllowed));
        details.put("batching", Boolean.toString(batchDelegate != null));
        if (delegate instanceof HealthContributor contributor) {
            try {
                ComponentHealth delegateHealth = contributor.health(componentName + ".delegate");
                status = HealthStatus.worst(status, delegateHealth.status());
                details.put("delegate_status",
                        delegateHealth.status().name().toLowerCase(java.util.Locale.ROOT));
            } catch (RuntimeException failure) {
                status = HealthStatus.FAILED;
                details.put("delegate_status", "failed");
                details.put("delegate_health_failure",
                        EmergencyText.failureSummary(failure, 512));
            }
        }

        Map<String, Long> metrics = new LinkedHashMap<>();
        metrics.put("capacity", (long) currentCapacity);
        metrics.put("queued", (long) currentQueued);
        metrics.put("active_deliveries", (long) activeDeliveries.get());
        metrics.put("outstanding_queued_events", (long) outstandingQueuedEvents.get());
        metrics.put("maximum_batch_size", (long) maximumBatchSize);
        metrics.put("enqueued_total", queuedEvents());
        metrics.put("delivered_total", deliveredEvents());
        metrics.put("dropped_total", droppedTotal);
        metrics.put("synchronous_fallback_total", synchronous);
        metrics.put("emergency_fallback_total", emergency);
        return new ComponentHealth(componentName, "output", status, details, metrics);
    }

    private boolean offerImmediately(LogEvent event) {
        outstandingQueuedEvents.incrementAndGet();
        if (!queue.offer(event)) {
            outstandingQueuedEvents.decrementAndGet();
            return false;
        }
        retainQueuedOffer(event);
        return true;
    }

    private void retainQueuedOffer(LogEvent event) {
        if (!accepting && queue.remove(event)) {
            outstandingQueuedEvents.decrementAndGet();
            emergencyFallbacks.increment();
            emergency(event, "output closed while the event was being enqueued");
            return;
        }
        queuedEvents.increment();
    }

    private void recordDrop(Level level) {
        dropped.get(level).increment();
        pendingDropReports.get(level).increment();
    }

    private void block(LogEvent event, Duration wait) {
        if (wait.isZero()) {
            emergencyFallbacks.increment();
            emergency(event, "async queue full with zero block timeout");
            return;
        }
        outstandingQueuedEvents.incrementAndGet();
        boolean offered = false;
        try {
            offered = queue.offer(event, saturatedNanos(wait), TimeUnit.NANOSECONDS);
            if (offered) {
                retainQueuedOffer(event);
            } else {
                emergencyFallbacks.increment();
                emergency(event, "async queue full after block timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            emergencyFallbacks.increment();
            emergency(event, "interrupted while waiting for logging queue");
        } finally {
            if (!offered) {
                outstandingQueuedEvents.decrementAndGet();
            }
        }
    }

    private boolean offerWithWait(LogEvent event, Duration wait) {
        outstandingQueuedEvents.incrementAndGet();
        boolean offered = false;
        try {
            offered = queue.offer(event, saturatedNanos(wait), TimeUnit.NANOSECONDS);
            if (offered) {
                retainQueuedOffer(event);
            }
            return offered;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (!offered) {
                outstandingQueuedEvents.decrementAndGet();
            }
        }
    }

    private void drainLoop() {
        try {
            while (running || !queue.isEmpty()) {
                try {
                    workerPolling = true;
                    LogEvent event;
                    try {
                        event = queue.poll(100, TimeUnit.MILLISECONDS);
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
                    internalStatus("output failure: "
                            + EmergencyText.failureSummary(failure, 2_048));
                }
            }
        } finally {
            if (closeDelegateOnExit) {
                try {
                    closeDelegate();
                } catch (RuntimeException failure) {
                    internalStatus("delegate close failure: "
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
                    next = queue.poll();
                } else {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        break;
                    }
                    workerPolling = true;
                    try {
                        next = queue.poll(remaining, TimeUnit.NANOSECONDS);
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
            deliverApplicationBatch(events);
        } finally {
            outstandingQueuedEvents.addAndGet(-events.size());
            activeDeliveries.decrementAndGet();
            if (interrupted && running) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void deliverApplicationBatch(List<LogEvent> events) {
        deliveryLock.lock();
        try {
            batchDelegate.acceptBatch(List.copyOf(events));
            deliveredEvents.add(events.size());
        } catch (RuntimeException failure) {
            emergencyFallbacks.add(events.size());
            int reported = Math.min(events.size(), 16);
            for (int index = 0; index < reported; index++) {
                emergency(events.get(index), "batch delegate failure: "
                        + failure.getClass().getSimpleName());
            }
            if (events.size() > reported) {
                internalStatus((events.size() - reported)
                        + " additional event(s) omitted from emergency output");
            }
            internalStatus("batch delegate failure: "
                    + EmergencyText.failureSummary(failure, 2_048));
        } finally {
            deliveryLock.unlock();
        }
    }

    private void deliverApplicationEvent(LogEvent event, boolean queuedDelivery) {
        activeDeliveries.incrementAndGet();
        deliveryLock.lock();
        try {
            delegate.accept(event);
            deliveredEvents.increment();
        } catch (RuntimeException failure) {
            emergencyFallbacks.increment();
            emergency(event, "delegate failure: " + failure.getClass().getSimpleName());
            internalStatus("delegate accept failure: "
                    + EmergencyText.failureSummary(failure, 2_048));
        } finally {
            deliveryLock.unlock();
            if (queuedDelivery) {
                outstandingQueuedEvents.decrementAndGet();
            }
            activeDeliveries.decrementAndGet();
        }
    }

    private void deliverInternalEvent(LogEvent event) {
        deliveryLock.lock();
        try {
            delegate.accept(event);
        } catch (RuntimeException failure) {
            internalStatus("failed to report dropped events: "
                    + EmergencyText.failureSummary(failure, 2_048));
        } finally {
            deliveryLock.unlock();
        }
    }

    private void emitDropSummary(boolean force) {
        long now = System.nanoTime();
        if (!force && now < nextDropReportNanos) {
            return;
        }
        nextDropReportNanos = now + TimeUnit.SECONDS.toNanos(10);
        AttributeSet.Builder attributes = AttributeSet.builder();
        long total = 0;
        for (Level level : Level.values()) {
            long count = pendingDropReports.get(level).sumThenReset();
            if (count > 0) {
                attributes.put("logyard.dropped." + level.name().toLowerCase(java.util.Locale.ROOT), count);
                total += count;
            }
        }
        if (total == 0) {
            return;
        }
        Thread thread = Thread.currentThread();
        long timestampMillis = System.currentTimeMillis();
        deliverInternalEvent(new LogEvent(
                timestampMillis,
                TimeUnit.MILLISECONDS.toNanos(timestampMillis),
                Level.WARN,
                "logyard.internal.async",
                "logyard.async.events_dropped",
                "Dropped {} log events because an output queue was full",
                new Object[] {total},
                attributes.put("logyard.dropped.total", total).build(),
                null,
                thread.threadId(),
                thread.getName()));
    }

    private boolean awaitQuiescence(Duration timeout) {
        long timeoutNanos = saturatedNanos(timeout);
        long started = System.nanoTime();
        while (outstandingQueuedEvents.get() != 0) {
            long elapsed = System.nanoTime() - started;
            if (elapsed >= timeoutNanos) {
                return false;
            }
            LockSupport.parkNanos(Math.min(100_000L, timeoutNanos - elapsed));
        }
        return true;
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
        while ((remaining = queue.poll()) != null) {
            outstandingQueuedEvents.decrementAndGet();
            emergencyFallbacks.increment();
            emergency(remaining, reason);
        }
    }

    private void closeDelegate() {
        if (!delegateCloseStarted.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        emitDropSummary(true);
        deliveryLock.lock();
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
            deliveryLock.unlock();
        }
        if (failure != null) {
            throw new IllegalStateException("failed to close Logyard async output '" + name + "'", failure);
        }
    }

    private void internalStatus(String message) {
        System.err.println("Logyard async output '"
                + EmergencyText.sanitize(name, 256)
                + "': " + EmergencyText.sanitize(message, 4_096));
    }

    private static void emergency(LogEvent event, String reason) {
        String message = EmergencyText.sanitize(event.renderedMessage(), 65_536);
        System.err.printf(
                "%s %-5s %s - %s [Logyard emergency path: %s]%n",
                Instant.ofEpochMilli(event.timestampMillis()),
                event.level(),
                EmergencyText.sanitize(event.loggerName(), 1_024),
                message,
                EmergencyText.sanitize(reason, 2_048));
        if (event.exception() != null) {
            printException(event.exception(), 0);
        }
    }


    private static void printException(ExceptionSnapshot exception, int depth) {
        if (depth > ExceptionSnapshot.MAX_CAUSE_DEPTH) {
            return;
        }
        String prefix = depth == 0 ? "" : "Caused by: ";
        System.err.println(prefix + EmergencyText.sanitize(exception.summary(), 16_384));
        int frames = Math.min(exception.frames().size(), 32);
        for (int index = 0; index < frames; index++) {
            System.err.println("    at " + EmergencyText.sanitize(exception.frames().get(index).toString(), 2_048));
        }
        if (exception.frames().size() > frames || exception.truncated()) {
            System.err.println("    ... exception snapshot bounded");
        }
        if (exception.cause() != null) {
            printException(exception.cause(), depth + 1);
        }
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
