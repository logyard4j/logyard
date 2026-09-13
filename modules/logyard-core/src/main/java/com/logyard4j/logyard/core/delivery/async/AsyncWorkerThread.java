package com.logyard4j.logyard.core.delivery.async;

import com.logyard4j.logyard.core.diagnostics.EmergencyText;

import java.time.Duration;

/** Owns the asynchronous output thread's naming, interruption, and bounded join behavior. */
final class AsyncWorkerThread {
    private final Thread thread;

    AsyncWorkerThread(String outputName, Runnable task) {
        thread = new Thread(task, "logyard-output-" + EmergencyText.threadComponent(outputName, 64));
        thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    boolean alive() {
        return thread.isAlive();
    }

    void interrupt() {
        thread.interrupt();
    }

    boolean await(Duration timeout) {
        if (!alive()) {
            return true;
        }
        long timeoutNanos = saturatedNanos(timeout);
        if (timeoutNanos == 0) {
            return false;
        }
        try {
            thread.join(timeoutNanos / 1_000_000L, (int) (timeoutNanos % 1_000_000L));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !alive();
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
