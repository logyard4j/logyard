package com.zsumz.logyard.output.json.flush;

import java.time.Duration;
import java.util.List;

/** Synchronizes ownership, retry, and dirty-period transitions for one timed flush controller. */
final class TimedFlushState {
    private final TimedFlushTasks tasks = new TimedFlushTasks();
    private final FlushDispatchRetry dispatchRetry = new FlushDispatchRetry();
    private boolean transportCompleted;
    private boolean rescheduleNeeded;
    private boolean closed;

    synchronized boolean closed() {
        return closed;
    }

    synchronized TimedFlushTask reserveForRecord() {
        if (closed) {
            return null;
        }
        if (tasks.occupied()) {
            rescheduleNeeded |= transportCompleted;
            return null;
        }
        TimedFlushTask task = new TimedFlushTask();
        tasks.own(task);
        return task;
    }

    synchronized TimedFlushTask cancelPending() {
        TimedFlushTask canceled = tasks.release();
        transportCompleted = false;
        rescheduleNeeded = false;
        dispatchRetry.reset();
        return canceled;
    }

    synchronized List<TimedFlushTask> close() {
        closed = true;
        List<TimedFlushTask> canceled = tasks.drain();
        transportCompleted = false;
        rescheduleNeeded = false;
        dispatchRetry.reset();
        return canceled;
    }

    synchronized Retry replaceAfterDispatchFailure(TimedFlushTask rejected) {
        if (!tasks.owns(rejected)) {
            return null;
        }
        tasks.releaseAndRetire(rejected, !closed);
        transportCompleted = false;
        rescheduleNeeded = false;
        if (closed) {
            return null;
        }
        TimedFlushTask retry = new TimedFlushTask();
        tasks.own(retry);
        return new Retry(retry, dispatchRetry.nextDelay());
    }

    synchronized boolean canRun(TimedFlushTask task) {
        return tasks.owns(task) && !closed;
    }

    synchronized boolean owns(TimedFlushTask task) {
        return tasks.owns(task);
    }

    synchronized boolean flushIsCurrent(Duration interval) {
        return !closed && (interval.isZero() || tasks.owned() != null && tasks.owned().runsOnCurrentThread());
    }

    synchronized void flushCompleted() {
        if (tasks.owned() != null && tasks.owned().runsOnCurrentThread()) {
            transportCompleted = true;
        }
    }

    synchronized boolean complete(TimedFlushTask task) {
        boolean scheduleNext = false;
        if (tasks.owns(task)) {
            tasks.release();
            scheduleNext = !closed && transportCompleted && rescheduleNeeded;
            if (transportCompleted) {
                dispatchRetry.reset();
            }
            transportCompleted = false;
            rescheduleNeeded = false;
        }
        if (!closed) {
            tasks.retire(task);
        }
        return scheduleNext;
    }

    synchronized void abandon(TimedFlushTask task) {
        tasks.releaseAndRetire(task, false);
    }

    record Retry(TimedFlushTask task, Duration delay) {
    }
}
