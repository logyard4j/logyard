package com.logyard4j.logyard.output.json.file.rotation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Owns the archive-maintenance thread's naming, interruption, and bounded shutdown wait. */
final class ArchiveMaintenanceWorker {
    private static final int MAX_THREAD_COMPONENT_LENGTH = 48;

    private final Thread thread;

    ArchiveMaintenanceWorker(Path activePath, Runnable task) {
        Objects.requireNonNull(activePath, "activePath");
        thread = new Thread(task, "logyard-archive-maintenance-" + sanitize(activePath.getFileName().toString()));
        thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    boolean alive() {
        return thread.isAlive();
    }

    void await(Duration timeout, Path activePath) {
        if (timeout.isZero()) {
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
        if (thread.isAlive()) {
            throw new IllegalStateException("archive maintenance did not stop within " + timeout + " for " + activePath);
        }
    }

    void awaitStopped() {
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static long saturatedMillis(Duration timeout) {
        try {
            return Math.max(1L, timeout.toMillis());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String sanitize(String value) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_THREAD_COMPONENT_LENGTH));
        for (int index = 0; index < value.length() && result.length() < MAX_THREAD_COMPONENT_LENGTH; index++) {
            char character = value.charAt(index);
            result.append(Character.isLetterOrDigit(character) || character == '-' || character == '_' ? character : '_');
        }
        return result.toString();
    }
}
