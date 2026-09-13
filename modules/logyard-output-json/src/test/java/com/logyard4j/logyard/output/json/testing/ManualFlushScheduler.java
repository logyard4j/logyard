package com.logyard4j.logyard.output.json.testing;

import com.logyard4j.logyard.output.json.flush.FlushScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Deterministic one-shot scheduler for JSON output lifecycle tests. */
public final class ManualFlushScheduler implements FlushScheduler {
    private final List<Task> tasks = new ArrayList<>();
    private final List<Duration> delays = new ArrayList<>();
    private int scheduled;

    @Override
    public synchronized ScheduledFlush schedule(Duration delay, Runnable action) {
        Task task = new Task(action);
        tasks.add(task);
        delays.add(delay);
        scheduled++;
        return task::cancel;
    }

    public synchronized int scheduledCount() {
        return scheduled;
    }

    public synchronized int pendingCount() {
        return (int) tasks.stream().filter(Task::pending).count();
    }

    public synchronized List<Duration> scheduledDelays() {
        return List.copyOf(delays);
    }

    public void runNext() {
        Task due;
        synchronized (this) {
            due = tasks.stream().filter(Task::pending).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no scheduled flush is pending"));
            due.begin();
        }
        due.run();
    }

    private static final class Task {
        private final Runnable action;
        private boolean canceled;
        private boolean started;

        private Task(Runnable action) {
            this.action = action;
        }

        synchronized boolean pending() {
            return !canceled && !started;
        }

        synchronized void cancel() {
            canceled = true;
        }

        synchronized void begin() {
            started = true;
        }

        void run() {
            action.run();
        }
    }
}
