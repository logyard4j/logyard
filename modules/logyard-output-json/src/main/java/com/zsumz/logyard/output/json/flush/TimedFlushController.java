package com.zsumz.logyard.output.json.flush;

import com.zsumz.logyard.api.annotation.InternalApi;

import java.time.Duration;
import java.util.Objects;

/** Maintains at most one pending one-shot flush for a dirty JSON output. */
@InternalApi
public final class TimedFlushController implements AutoCloseable {
    public static final Duration MAX_INTERVAL = Duration.ofMinutes(1L);

    private final Duration interval;
    private final FlushScheduler scheduler;
    private final Runnable flushAction;
    private PendingFlush pending;
    private boolean closed;

    public TimedFlushController(Duration interval, Runnable flushAction) {
        this(interval, SharedFlushScheduler.INSTANCE, flushAction);
    }

    public TimedFlushController(Duration interval, FlushScheduler scheduler, Runnable flushAction) {
        this.interval = validInterval(interval);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.flushAction = Objects.requireNonNull(flushAction, "flushAction");
    }

    public void recordWritten() {
        if (interval.isZero()) {
            synchronized (this) {
                if (closed) {
                    return;
                }
            }
            flushAction.run();
            return;
        }

        PendingFlush candidate;
        synchronized (this) {
            if (closed || pending != null) {
                return;
            }
            candidate = new PendingFlush();
            pending = candidate;
        }

        try {
            FlushScheduler.ScheduledFlush scheduled = scheduler.schedule(interval, () -> runDue(candidate));
            candidate.attach(scheduled);
            synchronized (this) {
                if (pending != candidate) {
                    candidate.cancel();
                }
            }
        } catch (RuntimeException schedulingFailure) {
            synchronized (this) {
                if (pending == candidate) {
                    pending = null;
                }
            }
            flushAction.run();
        }
    }

    public void flushed() {
        cancelPending();
    }

    public void cancelPending() {
        PendingFlush canceled;
        synchronized (this) {
            canceled = pending;
            pending = null;
        }
        if (canceled != null) {
            canceled.cancel();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        cancelPending();
    }

    private void runDue(PendingFlush due) {
        synchronized (this) {
            if (pending != due || closed) {
                return;
            }
            pending = null;
        }
        flushAction.run();
    }

    private static Duration validInterval(Duration interval) {
        Objects.requireNonNull(interval, "flushInterval");
        if (interval.isNegative() || interval.compareTo(MAX_INTERVAL) > 0) {
            throw new IllegalArgumentException("flush interval must be between 0s and 1m");
        }
        return interval;
    }

    private static final class PendingFlush {
        private FlushScheduler.ScheduledFlush scheduled;
        private boolean canceled;

        synchronized void attach(FlushScheduler.ScheduledFlush value) {
            scheduled = Objects.requireNonNull(value, "scheduled flush");
            if (canceled) {
                scheduled.cancel();
            }
        }

        synchronized void cancel() {
            canceled = true;
            if (scheduled != null) {
                scheduled.cancel();
            }
        }
    }
}
