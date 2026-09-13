package com.logyard4j.logyard.output.json.flush;

import com.logyard4j.logyard.api.annotation.InternalApi;
import com.logyard4j.logyard.api.failure.FailureIsolation;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Schedules at most one pending one-shot flush while {@link TimedFlushState} owns its transitions. */
@InternalApi
public final class TimedFlushController implements AutoCloseable {
    public static final Duration MAX_INTERVAL = TimedFlushInterval.MAXIMUM;

    private final Duration interval;
    private final FlushScheduler scheduler;
    private final FlushDispatcher dispatcher;
    private final FlushDiagnostics diagnostics;
    private final Runnable flushAction;
    private final TimedFlushState state = new TimedFlushState();

    public TimedFlushController(Duration interval, Runnable flushAction) {
        this(interval, SharedFlushScheduler.INSTANCE, BoundedElasticFlushDispatcher.INSTANCE, FlushDiagnostics.STDERR, flushAction);
    }

    public TimedFlushController(Duration interval, FlushScheduler scheduler, Runnable flushAction) {
        this(interval, scheduler, BoundedElasticFlushDispatcher.INSTANCE, FlushDiagnostics.STDERR, flushAction);
    }

    TimedFlushController(Duration interval, FlushScheduler scheduler, FlushDispatcher dispatcher, FlushDiagnostics diagnostics, Runnable flushAction) {
        this.interval = TimedFlushInterval.requireValid(interval);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.flushAction = Objects.requireNonNull(flushAction, "flushAction");
    }

    public void recordWritten() {
        if (interval.isZero()) {
            if (!state.closed()) {
                flushAction.run();
            }
            return;
        }
        TimedFlushTask task = state.reserveForRecord();
        if (task != null) {
            scheduleOrRunInline(task, interval);
        }
    }

    public void flushed() {
        cancel(state.cancelPending());
    }

    public void cancelPending() {
        cancel(state.cancelPending());
    }

    @Override
    public void close() {
        List<TimedFlushTask> canceled = state.close();
        canceled.forEach(TimedFlushTask::cancel);
        canceled.forEach(TimedFlushTask::awaitCompletion);
    }

    /** Reports whether the caller still owns the current zero-interval or dispatched flush. */
    public boolean flushIsCurrent() {
        return state.flushIsCurrent(interval);
    }

    /** Completes the current dispatched flush while its transport serialization lock is still held. */
    public void flushCompleted() {
        state.flushCompleted();
    }

    private void runDue(TimedFlushTask due) {
        if (!due.beginDispatch()) {
            return;
        }
        try {
            due.attachDispatched(dispatcher.dispatch(() -> runDispatched(due)));
        } catch (Throwable dispatchFailure) {
            due.dispatchFailed();
            scheduleRetryAfterDispatchFailure(due);
            FailureIsolation.prepareForRecovery(dispatchFailure);
            diagnostics.report(dispatchFailure);
        }
    }

    private void scheduleRetryAfterDispatchFailure(TimedFlushTask rejected) {
        TimedFlushState.Retry retry = state.replaceAfterDispatchFailure(rejected);
        if (retry != null) {
            scheduleOrRunInline(retry.task(), retry.delay());
        }
    }

    private void runDispatched(TimedFlushTask due) {
        due.runner(Thread.currentThread());
        try {
            if (state.canRun(due)) {
                flushAction.run();
            }
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
            if (state.canRun(due)) {
                flushAction.run();
            }
        } finally {
            complete(due);
        }
    }

    private void complete(TimedFlushTask due) {
        if (state.complete(due)) {
            recordWritten();
        }
    }

    private void attachScheduled(TimedFlushTask task, Duration delay) {
        task.attachScheduled(scheduler.schedule(delay, () -> runDue(task)));
        if (!state.owns(task)) {
            task.cancel();
        }
    }

    private void scheduleOrRunInline(TimedFlushTask task, Duration delay) {
        try {
            attachScheduled(task, delay);
        } catch (Throwable schedulingFailure) {
            try {
                FailureIsolation.prepareForRecovery(schedulingFailure);
            } catch (Throwable fatalFailure) {
                state.abandon(task);
                task.cancel();
                throw fatalFailure;
            }
            try {
                runInline(task);
            } finally {
                diagnostics.report(schedulingFailure);
            }
        }
    }

    private static void cancel(TimedFlushTask task) {
        if (task != null) {
            task.cancel();
        }
    }
}
