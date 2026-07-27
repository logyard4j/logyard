package com.zsumz.logyard.runtime.reload.watcher;

import java.nio.file.Path;
import java.time.Duration;

/** Owns the watcher thread's name, start, interruption, and bounded join behavior. */
final class ConfigurationWatchWorker {
    private static final int MAX_THREAD_COMPONENT_LENGTH = 48;

    private final Thread thread;

    ConfigurationWatchWorker(Path source, Runnable task) {
        thread = new Thread(task, "logyard-config-watch-" + safeThreadSegment(source.getFileName().toString()));
        thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    boolean isAlive() {
        return thread.isAlive();
    }

    void interrupt() {
        thread.interrupt();
    }

    void await(Duration timeout, Path source) {
        if (timeout.isZero() || !isAlive()) {
            return;
        }
        boolean interrupted = false;
        try {
            thread.join(saturatedMillis(timeout));
        } catch (InterruptedException interruption) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (isAlive()) {
            throw new IllegalStateException("configuration watcher did not stop within " + timeout + " for " + source);
        }
    }

    private static long saturatedMillis(Duration duration) {
        try {
            return Math.max(1L, duration.toMillis());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String safeThreadSegment(String value) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_THREAD_COMPONENT_LENGTH));
        for (int index = 0; index < value.length() && result.length() < MAX_THREAD_COMPONENT_LENGTH; index++) {
            char character = value.charAt(index);
            result.append(Character.isLetterOrDigit(character) || character == '-' || character == '_' ? character : '_');
        }
        return result.toString();
    }
}
