package com.zsumz.logyard.output.json.flush;

import java.util.Objects;

/** Cancellation and completion state for one pending or dispatched flush. */
final class TimedFlushTask {
    private final boolean retry;
    private FlushScheduler.ScheduledFlush scheduled;
    private FlushDispatcher.DispatchedFlush dispatched;
    private Thread runner;
    private boolean canceled;
    private boolean dispatching;

    TimedFlushTask(boolean retry) {
        this.retry = retry;
    }

    boolean retry() {
        return retry;
    }

    void attachScheduled(FlushScheduler.ScheduledFlush value) {
        boolean cancel;
        synchronized (this) {
            scheduled = Objects.requireNonNull(value, "scheduled flush");
            cancel = canceled;
        }
        if (cancel) {
            value.cancel();
        }
    }

    synchronized boolean beginDispatch() {
        if (canceled) {
            return false;
        }
        dispatching = true;
        return true;
    }

    void attachDispatched(FlushDispatcher.DispatchedFlush value) {
        boolean cancel;
        synchronized (this) {
            dispatched = Objects.requireNonNull(value, "dispatched flush");
            dispatching = false;
            cancel = canceled;
            notifyAll();
        }
        if (cancel) {
            value.cancel();
        }
    }

    synchronized void dispatchFailed() {
        dispatching = false;
        notifyAll();
    }

    synchronized void runner(Thread value) {
        runner = Objects.requireNonNull(value, "runner");
    }

    synchronized boolean runsOnCurrentThread() {
        return runner == Thread.currentThread();
    }

    void cancel() {
        FlushScheduler.ScheduledFlush scheduledToCancel;
        FlushDispatcher.DispatchedFlush dispatchedToCancel;
        synchronized (this) {
            canceled = true;
            scheduledToCancel = scheduled;
            dispatchedToCancel = dispatched;
        }
        if (scheduledToCancel != null) {
            scheduledToCancel.cancel();
        }
        if (dispatchedToCancel != null) {
            dispatchedToCancel.cancel();
        }
    }

    void awaitCompletion() {
        FlushDispatcher.DispatchedFlush dispatchedToAwait;
        boolean interrupted = false;
        synchronized (this) {
            while (dispatching) {
                try {
                    wait();
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
            dispatchedToAwait = dispatched;
        }
        if (dispatchedToAwait != null) {
            dispatchedToAwait.awaitCompletion();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    boolean completed() {
        FlushDispatcher.DispatchedFlush current;
        synchronized (this) {
            if (dispatching) {
                return false;
            }
            current = dispatched;
        }
        return current == null || current.completed();
    }
}
