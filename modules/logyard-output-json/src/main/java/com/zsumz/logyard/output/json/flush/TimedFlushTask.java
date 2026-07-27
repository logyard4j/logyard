package com.zsumz.logyard.output.json.flush;

import java.util.Objects;

/** Cancellation and completion state for one pending or dispatched flush. */
final class TimedFlushTask {
    private FlushScheduler.ScheduledFlush scheduled;
    private FlushDispatcher.DispatchedFlush dispatched;
    private Thread runner;
    private Phase phase = Phase.SCHEDULED;

    void attachScheduled(FlushScheduler.ScheduledFlush value) {
        FlushScheduler.ScheduledFlush cancellation = null;
        synchronized (this) {
            scheduled = Objects.requireNonNull(value, "scheduled flush");
            if (phase == Phase.CANCELED) {
                cancellation = value;
            }
        }
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    synchronized boolean beginDispatch() {
        if (phase != Phase.SCHEDULED) {
            return false;
        }
        phase = Phase.DISPATCHING;
        return true;
    }

    void attachDispatched(FlushDispatcher.DispatchedFlush value) {
        FlushDispatcher.DispatchedFlush cancellation = null;
        synchronized (this) {
            dispatched = Objects.requireNonNull(value, "dispatched flush");
            if (phase == Phase.CANCELED) {
                cancellation = value;
            } else {
                phase = Phase.DISPATCHED;
            }
            notifyAll();
        }
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    synchronized void dispatchFailed() {
        if (phase == Phase.DISPATCHING) {
            phase = Phase.DISPATCH_FAILED;
        }
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
            phase = Phase.CANCELED;
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
            while (phase == Phase.DISPATCHING) {
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
            if (phase == Phase.DISPATCHING) {
                return false;
            }
            current = dispatched;
        }
        return current == null || current.completed();
    }

    private enum Phase {
        SCHEDULED,
        DISPATCHING,
        DISPATCHED,
        DISPATCH_FAILED,
        CANCELED
    }
}
