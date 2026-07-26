package com.zsumz.logyard.output.json.file.rotation;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Wakes an idle archive worker without allowing shutdown interrupts to reach active file I/O. */
final class ArchiveQueuePoll {
    private final ArrayBlockingQueue<Path> queue;
    private final AtomicBoolean closing;
    private final Object monitor = new Object();
    private boolean polling;

    ArchiveQueuePoll(ArrayBlockingQueue<Path> queue, AtomicBoolean closing) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.closing = Objects.requireNonNull(closing, "closing");
    }

    Path next() throws InterruptedException {
        synchronized (monitor) {
            if (closing.get() && queue.isEmpty()) {
                return null;
            }
            polling = true;
        }
        try {
            return queue.poll(100L, TimeUnit.MILLISECONDS);
        } finally {
            synchronized (monitor) {
                polling = false;
                if (closing.get()) {
                    Thread.interrupted();
                }
            }
        }
    }

    void interruptIfPolling(Thread worker) {
        synchronized (monitor) {
            if (polling) {
                worker.interrupt();
            }
        }
    }
}
