package com.zsumz.logyard.output.json.flush;

import java.time.Duration;
import java.util.List;

/** Synchronizes ownership, retry, and dirty-period transitions for one timed flush controller. */
final class TimedFlushState {
    private final TimedFlushTasks tasks = new TimedFlushTasks();
    private final FlushDispatchRetry dispatchRetry = new FlushDispatchRetry();
    private ControllerPhase phase = ControllerPhase.OPEN;
    private DirtyCycle dirtyCycle = DirtyCycle.CLEAN;

    synchronized boolean closed() {
        return phase == ControllerPhase.CLOSED;
    }

    synchronized TimedFlushTask reserveForRecord() {
        if (phase == ControllerPhase.CLOSED) {
            return null;
        }
        if (tasks.occupied()) {
            dirtyCycle = dirtyCycle.afterRecord();
            return null;
        }
        TimedFlushTask task = new TimedFlushTask();
        tasks.own(task);
        return task;
    }

    synchronized TimedFlushTask cancelPending() {
        TimedFlushTask canceled = tasks.release();
        dirtyCycle = DirtyCycle.CLEAN;
        dispatchRetry.reset();
        return canceled;
    }

    synchronized List<TimedFlushTask> close() {
        phase = ControllerPhase.CLOSED;
        List<TimedFlushTask> canceled = tasks.drain();
        dirtyCycle = DirtyCycle.CLEAN;
        dispatchRetry.reset();
        return canceled;
    }

    synchronized Retry replaceAfterDispatchFailure(TimedFlushTask rejected) {
        if (!tasks.owns(rejected)) {
            return null;
        }
        tasks.releaseAndRetire(rejected, phase == ControllerPhase.OPEN);
        dirtyCycle = DirtyCycle.CLEAN;
        if (phase == ControllerPhase.CLOSED) {
            return null;
        }
        TimedFlushTask retry = new TimedFlushTask();
        tasks.own(retry);
        return new Retry(retry, dispatchRetry.nextDelay());
    }

    synchronized boolean canRun(TimedFlushTask task) {
        return tasks.owns(task) && phase == ControllerPhase.OPEN;
    }

    synchronized boolean owns(TimedFlushTask task) {
        return tasks.owns(task);
    }

    synchronized boolean flushIsCurrent(Duration interval) {
        return phase == ControllerPhase.OPEN && (interval.isZero() || tasks.owned() != null && tasks.owned().runsOnCurrentThread());
    }

    synchronized void flushCompleted() {
        if (tasks.owned() != null && tasks.owned().runsOnCurrentThread()) {
            dirtyCycle = dirtyCycle.afterFlush();
        }
    }

    synchronized boolean complete(TimedFlushTask task) {
        if (!tasks.owns(task)) {
            if (phase == ControllerPhase.OPEN) {
                tasks.retire(task);
            }
            return false;
        }
        DirtyCycle completedCycle = dirtyCycle;
        tasks.release();
        if (completedCycle != DirtyCycle.CLEAN) {
            dispatchRetry.reset();
        }
        dirtyCycle = DirtyCycle.CLEAN;
        if (phase == ControllerPhase.OPEN) {
            tasks.retire(task);
        }
        return phase == ControllerPhase.OPEN && completedCycle == DirtyCycle.RESCHEDULE_REQUIRED;
    }

    synchronized void abandon(TimedFlushTask task) {
        tasks.releaseAndRetire(task, false);
    }

    record Retry(TimedFlushTask task, Duration delay) {
    }

    private enum ControllerPhase {
        OPEN,
        CLOSED
    }

    private enum DirtyCycle {
        CLEAN,
        FLUSH_COMPLETED,
        RESCHEDULE_REQUIRED;

        DirtyCycle afterRecord() {
            return this == FLUSH_COMPLETED ? RESCHEDULE_REQUIRED : this;
        }

        DirtyCycle afterFlush() {
            return this == RESCHEDULE_REQUIRED ? RESCHEDULE_REQUIRED : FLUSH_COMPLETED;
        }
    }
}
