package com.logyard4j.output.json.flush;

import java.util.ArrayList;
import java.util.List;

/** Tracks the single owned deadline and bounded set of workers retiring from it. */
final class TimedFlushTasks {
    private final List<TimedFlushTask> retiring = new ArrayList<>(1);
    private TimedFlushTask owned;

    boolean occupied() {
        return owned != null;
    }

    boolean owns(TimedFlushTask task) {
        return owned == task;
    }

    TimedFlushTask owned() {
        return owned;
    }

    void own(TimedFlushTask task) {
        if (owned != null) {
            throw new IllegalStateException("a timed flush is already owned");
        }
        owned = task;
    }

    TimedFlushTask release() {
        TimedFlushTask released = owned;
        owned = null;
        return released;
    }

    void releaseAndRetire(TimedFlushTask task, boolean retainUntilCompletion) {
        if (owns(task)) {
            release();
        }
        if (retainUntilCompletion) {
            retire(task);
        }
    }

    void retire(TimedFlushTask task) {
        retiring.removeIf(TimedFlushTask::completed);
        retiring.add(task);
    }

    List<TimedFlushTask> drain() {
        List<TimedFlushTask> drained = new ArrayList<>(retiring.size() + 1);
        drained.addAll(retiring);
        if (owned != null) {
            drained.add(owned);
        }
        retiring.clear();
        owned = null;
        return drained;
    }
}
