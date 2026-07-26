package com.zsumz.logyard.output.json.flush;

import com.zsumz.logyard.api.annotation.InternalApi;
import com.zsumz.logyard.api.failure.FailureIsolation;

import java.time.Duration;
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
    private final TimedFlushTasks tasks = new TimedFlushTasks();
    private final FlushDispatchRetry dispatchRetry = new FlushDispatchRetry();
    private boolean transportCompleted;
    private boolean rescheduleNeeded;
    private boolean closed;

    public TimedFlushController(Duration interval, Runnable flushAction) {
        this(interval, SharedFlushScheduler.INSTANCE, BoundedElasticFlushDispatcher.INSTANCE, FlushDiagnostics.STDERR, flushAction);
    }

    public TimedFlushController(Duration interval, FlushScheduler scheduler, Runnable flushAction) {
        this(interval, scheduler, BoundedElasticFlushDispatcher.INSTANCE, FlushDiagnostics.STDERR, flushAction);
    }

    TimedFlushController(Duration interval, FlushScheduler scheduler, FlushDispatcher dispatcher, FlushDiagnostics diagnostics, Runnable flushAction) {
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
            if (tasks.occupied()) {
                rescheduleNeeded |= transportCompleted;
                return;
            }
            candidate = new TimedFlushTask();
            tasks.own(candidate);
        }

        scheduleInitial(candidate);
    }

    private void scheduleInitial(TimedFlushTask candidate) {
        try {
            attachScheduled(candidate, interval);
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
            canceled = tasks.release();
            transportCompleted = false;
            rescheduleNeeded = false;
            dispatchRetry.reset();
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
            canceled = tasks.drain();
            transportCompleted = false;
            rescheduleNeeded = false;
            dispatchRetry.reset();
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
            FailureIsolation.prepareForRecovery(dispatchFailure);
            diagnostics.report(dispatchFailure);
            scheduleRetryAfterDispatchFailure(due);
        }
    }

    private void scheduleRetryAfterDispatchFailure(TimedFlushTask rejected) {
        TimedFlushTask retry;
        Duration retryDelay;
        synchronized (this) {
            if (!tasks.owns(rejected)) {
                return;
            }
            tasks.releaseAndRetire(rejected, !closed);
            transportCompleted = false;
            rescheduleNeeded = false;
            if (closed) {
                return;
            }
            retry = new TimedFlushTask();
            tasks.own(retry);
            retryDelay = dispatchRetry.nextDelay();
        }
        try {
            attachScheduled(retry, retryDelay);
        } catch (Throwable schedulingFailure) {
            abandonRetry(retry);
            FailureIsolation.prepareForRecovery(schedulingFailure);
            diagnostics.report(schedulingFailure);
        }
    }

    private void runDispatched(TimedFlushTask due) {
        due.runner(Thread.currentThread());
        try {
            synchronized (this) {
                if (!tasks.owns(due) || closed) {
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
                if (!tasks.owns(due) || closed) {
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
        return !closed && (interval.isZero() || tasks.owned() != null && tasks.owned().runsOnCurrentThread());
    }

    /** Completes the current dispatched flush while its transport serialization lock is still held. */
    public void flushCompleted() {
        synchronized (this) {
            if (tasks.owned() == null || !tasks.owned().runsOnCurrentThread()) {
                return;
            }
            transportCompleted = true;
        }
    }

    private void complete(TimedFlushTask due) {
        boolean scheduleNext = false;
        synchronized (this) {
            if (tasks.owns(due)) {
                tasks.release();
                scheduleNext = !closed && transportCompleted && rescheduleNeeded;
                if (transportCompleted) {
                    dispatchRetry.reset();
                }
                transportCompleted = false;
                rescheduleNeeded = false;
            }
            if (!closed) {
                tasks.retire(due);
            }
        }
        if (scheduleNext) {
            recordWritten();
        }
    }

    private void attachScheduled(TimedFlushTask candidate, Duration delay) {
        candidate.attachScheduled(scheduler.schedule(delay, () -> runDue(candidate)));
        boolean cancel;
        synchronized (this) {
            cancel = !tasks.owns(candidate);
        }
        if (cancel) {
            candidate.cancel();
        }
    }

    private void abandonRetry(TimedFlushTask retry) {
        synchronized (this) {
            tasks.releaseAndRetire(retry, !closed);
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
