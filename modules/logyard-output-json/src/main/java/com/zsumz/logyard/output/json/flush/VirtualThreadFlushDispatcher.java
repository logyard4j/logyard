package com.zsumz.logyard.output.json.flush;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Runs due flush I/O on short-lived Java 21 virtual threads. */
final class VirtualThreadFlushDispatcher implements FlushDispatcher {
    static final VirtualThreadFlushDispatcher INSTANCE = new VirtualThreadFlushDispatcher();

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private VirtualThreadFlushDispatcher() {
    }

    @Override
    public DispatchedFlush dispatch(Runnable action) {
        Thread thread = Thread.ofVirtual()
                .name("logyard-json-flush-io-" + SEQUENCE.incrementAndGet())
                .inheritInheritableThreadLocals(false)
                .unstarted(Objects.requireNonNull(action, "action"));
        thread.setContextClassLoader(null);
        thread.start();
        return new VirtualFlush(thread);
    }

    private record VirtualFlush(Thread thread) implements DispatchedFlush {
        @Override
        public void cancel() {
            thread.interrupt();
        }

        @Override
        public void awaitCompletion() {
            if (thread == Thread.currentThread()) {
                return;
            }
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

        @Override
        public boolean completed() {
            return !thread.isAlive();
        }
    }
}
