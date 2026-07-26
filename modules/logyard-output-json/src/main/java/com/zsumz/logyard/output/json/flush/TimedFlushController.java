package com.zsumz.logyard.output.json.flush;

import com.zsumz.logyard.api.annotation.InternalApi;
import com.zsumz.logyard.api.failure.FailureIsolation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Maintains at most one pending one-shot flush for a dirty JSON output. */
@InternalApi
public final class TimedFlushController implements AutoCloseable {
    public static final Duration MAX_INTERVAL = Duration.ofMinutes(1L);

    private final Duration interval;
    private final FlushScheduler scheduler;
    private final FlushDispatcher dispatcher;
    private final FlushDiagnostics diagnostics;
    private final Runnable flushAction;
    private final List<TimedFlushTask> retiring = new ArrayList<>(1);
    private TimedFlushTask owned;
    private boolean transportCompleted;
    private boolean rescheduleNeeded;
    private boolean closed;

    public TimedFlushController(Duration interval, Runnable flushAction) {
        this(interval, SharedFlushScheduler.INSTANCE, VirtualThreadFlushDispatcher.INSTANCE, FlushDiagnostics.STDERR, flushAction);
    }

    public TimedFlushController(Duration interval, FlushScheduler scheduler, Runnable flushAction) {
        this(interval, scheduler, VirtualThreadFlushDispatcher.INSTANCE, FlushDiagnostics.STDERR, flushAction);
    }

    TimedFlushController(
            Duration interval, FlushScheduler scheduler, FlushDispatcher dispatcher, FlushDiagnostics diagnostics, Runnable flushAction) {
        this.interval = validInterval(interval);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
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

        TimedFlushTask candidate;
        synchronized (this) {
            if (closed) {
                return;
            }
            if (owned != null) {
                rescheduleNeeded |= transportCompleted;
                return;
            }
            candidate = new TimedFlushTask();
            owned = candidate;
        }

        try {
            FlushScheduler.ScheduledFlush scheduled = scheduler.schedule(interval, () -> runDue(candidate));
            candidate.attachScheduled(scheduled);
            boolean cancel;
            synchronized (this) {
                cancel = owned != candidate;
            }
            if (cancel) {
                candidate.cancel();
            }
        } catch (Throwable schedulingFailure) {
            FailureIsolation.prepareForRecovery(schedulingFailure);
            diagnostics.report(schedulingFailure);
            runInline(candidate);
        }
    }

    public void flushed() { cancelPending(); }

    public void cancelPending() {
        TimedFlushTask canceled;
        synchronized (this) {
            canceled = owned;
            owned = null;
            transportCompleted = false;
            rescheduleNeeded = false;
        }
        if (canceled != null) {
            canceled.cancel();
        }
    }

    @Override
    public void close() {
        List<TimedFlushTask> canceled;
        synchronized (this) {
            closed = true;
            canceled = new ArrayList<>(retiring.size() + 1);
            canceled.addAll(retiring);
            if (owned != null) {
                canceled.add(owned);
            }
            retiring.clear();
            owned = null;
            transportCompleted = false;
            rescheduleNeeded = false;
        }
        canceled.forEach(TimedFlushTask::cancel);
        canceled.forEach(TimedFlushTask::awaitCompletion);
    }

    private void runDue(TimedFlushTask due) {
        if (!due.beginDispatch()) {
            return;
        }
        try {
            due.attachDispatched(dispatcher.dispatch(() -> runDispatched(due)));
        } catch (Throwable dispatchFailure) {
            due.dispatchFailed();
            complete(due);
            FailureIsolation.prepareForRecovery(dispatchFailure);
            diagnostics.report(dispatchFailure);
        }
    }

    private void runDispatched(TimedFlushTask due) {
        due.runner(Thread.currentThread());
        try {
            synchronized (this) {
                if (owned != due || closed) {
                    return;
                }
            }
            flushAction.run();
        } catch (Throwable failure) {
            FailureIsolation.prepareForRecovery(failure);
            diagnostics.report(failure);
        } finally {
            complete(due);
        }
    }

    private void runInline(TimedFlushTask due) {
        due.runner(Thread.currentThread());
        try {
            synchronized (this) {
                if (owned != due || closed) {
                    return;
                }
            }
            flushAction.run();
        } finally {
            complete(due);
        }
    }

    /** Reports whether the caller still owns the current zero-interval or dispatched flush. */
    public synchronized boolean flushIsCurrent() {
        return !closed && (interval.isZero() || owned != null && owned.runsOnCurrentThread());
    }

    /** Completes the current dispatched flush while its transport serialization lock is still held. */
    public void flushCompleted() {
        synchronized (this) {
            if (owned == null || !owned.runsOnCurrentThread()) {
                return;
            }
            transportCompleted = true;
        }
    }

    private void complete(TimedFlushTask due) {
        boolean scheduleNext = false;
        synchronized (this) {
            retiring.removeIf(TimedFlushTask::completed);
            if (owned == due) {
                owned = null;
                scheduleNext = !closed && transportCompleted && rescheduleNeeded;
                transportCompleted = false;
                rescheduleNeeded = false;
            }
            if (!closed) {
                retiring.add(due);
            }
        }
        if (scheduleNext) {
            recordWritten();
        }
    }

    private static Duration validInterval(Duration interval) {
        Objects.requireNonNull(interval, "flushInterval");
        if (interval.isNegative() || interval.compareTo(MAX_INTERVAL) > 0) {
            throw new IllegalArgumentException("flush interval must be between 0s and 1m");
        }
        return interval;
    }
}
