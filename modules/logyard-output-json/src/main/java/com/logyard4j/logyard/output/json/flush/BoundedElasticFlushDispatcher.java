package com.logyard4j.logyard.output.json.flush;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs blocking flush I/O on a shared bounded set of temporary daemon platform threads. */
final class BoundedElasticFlushDispatcher implements FlushDispatcher {
    static final int MAXIMUM_WORKERS = 128;
    static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(2L);
    static final BoundedElasticFlushDispatcher INSTANCE =
            new BoundedElasticFlushDispatcher(MAXIMUM_WORKERS, DEFAULT_IDLE_TIMEOUT);
    static final String WORKER_NAME_PREFIX = "logyard-json-flush-worker-";

    private final ThreadPoolExecutor workers;

    BoundedElasticFlushDispatcher(int maximumWorkers, Duration idleTimeout) {
        if (maximumWorkers < 1) {
            throw new IllegalArgumentException("maximumWorkers must be positive");
        }
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must be positive");
        }
        workers = new ThreadPoolExecutor(
                0,
                maximumWorkers,
                idleTimeout.toNanos(),
                TimeUnit.NANOSECONDS,
                new SynchronousQueue<>(),
                new FlushWorkerFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public DispatchedFlush dispatch(Runnable action) {
        DispatchTask dispatched = new DispatchTask(action);
        workers.execute(dispatched);
        return dispatched;
    }

    int liveWorkers() {
        return workers.getPoolSize();
    }

    private static final class FlushWorkerFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(
                    null,
                    task,
                    WORKER_NAME_PREFIX + sequence.incrementAndGet(),
                    0L,
                    false);
            thread.setDaemon(true);
            thread.setContextClassLoader(null);
            return thread;
        }
    }

    private static final class DispatchTask implements Runnable, DispatchedFlush {
        private final Runnable action;
        private final CountDownLatch completed = new CountDownLatch(1);
        private Thread runner;
        private TaskPhase phase = TaskPhase.QUEUED;

        private DispatchTask(Runnable action) {
            this.action = Objects.requireNonNull(action, "action");
        }

        @Override
        public void run() {
            Thread current = Thread.currentThread();
            synchronized (this) {
                if (phase == TaskPhase.CANCELED) {
                    completed.countDown();
                    return;
                }
                phase = TaskPhase.RUNNING;
                runner = current;
            }
            try {
                action.run();
            } finally {
                try {
                    if (current.getContextClassLoader() != null) {
                        current.setContextClassLoader(null);
                    }
                } finally {
                    synchronized (this) {
                        runner = null;
                        if (phase != TaskPhase.CANCELED) {
                            phase = TaskPhase.COMPLETED;
                        }
                    }
                    completed.countDown();
                }
            }
        }

        @Override
        public void cancel() {
            Thread running;
            synchronized (this) {
                if (phase == TaskPhase.QUEUED || phase == TaskPhase.RUNNING) {
                    phase = TaskPhase.CANCELED;
                }
                running = runner;
            }
            if (running != null) {
                running.interrupt();
            }
        }

        @Override
        public void awaitCompletion() {
            boolean interrupted = false;
            while (true) {
                try {
                    completed.await();
                    break;
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
            return completed.getCount() == 0L;
        }

        private enum TaskPhase {
            QUEUED,
            RUNNING,
            CANCELED,
            COMPLETED
        }
    }
}
