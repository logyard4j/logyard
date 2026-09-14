package com.logyard4j.logyard.compare;

import org.slf4j.Logger;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

/** Bounds producer concurrency and records arrivals before dispatching fresh virtual requests. */
public final class ProducerTeam implements AutoCloseable {
    private enum Phase { WARMUP, MEASUREMENT, CLOSED }
    private final RunOptions options;
    private final List<Thread> threads = new ArrayList<>();
    private final CountDownLatch warmed;
    private final CountDownLatch start = new CountDownLatch(1);
    private final CountDownLatch done;
    private final CountDownLatch finish = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile Phase phase = Phase.WARMUP;
    private volatile long startedAt;
    private final long[] arrivals;
    private final long[] callerTimes;
    private final long[] lateness;

    public ProducerTeam(RunOptions options, ProducerWorkload workload, Logger logger) {
        this.options = options;
        warmed = new CountDownLatch(options.producers());
        done = new CountDownLatch(options.producers());
        arrivals = new long[options.events()];
        callerTimes = new long[options.events()];
        lateness = new long[options.events()];
        for (int index = 0; index < options.producers(); index++) {
            int producer = index;
            Thread.Builder builder = options.threadModel() == ThreadModel.VIRTUAL
                    ? Thread.ofVirtual() : Thread.ofPlatform().daemon(true);
            threads.add(builder.name("comparison-producer-" + index).start(() -> run(producer, workload, logger)));
        }
    }

    public void awaitWarmup() throws InterruptedException {
        require(warmed.await(30, TimeUnit.SECONDS), "producer warmup timed out");
        require(phase != Phase.CLOSED, "producer warmup was canceled");
        requireHealthy();
    }

    public long begin() {
        require(phase == Phase.WARMUP && warmed.getCount() == 0, "producers are not ready for measurement");
        startedAt = System.nanoTime();
        phase = Phase.MEASUREMENT;
        start.countDown();
        return startedAt;
    }

    public void awaitCalls() throws InterruptedException {
        require(done.await(90, TimeUnit.SECONDS), "producer calls timed out");
        require(phase == Phase.MEASUREMENT, "producer measurement was canceled");
        requireHealthy();
    }

    public boolean awaitCalls(long timeout, TimeUnit unit) throws InterruptedException {
        boolean completed = done.await(timeout, unit);
        require(phase == Phase.MEASUREMENT, "producer measurement was canceled");
        requireHealthy();
        return completed;
    }

    public Set<Long> threadIds() {
        // Fresh virtual callers have already terminated at the metrics boundary; dispatchers are overhead.
        if (options.virtualPerRequest()) return Set.of();
        return threads.stream().map(Thread::threadId).collect(Collectors.toSet());
    }

    public long[] arrivals() {
        return arrivals;
    }

    public long[] callerTimes() {
        return callerTimes;
    }

    public long[] lateness() {
        return lateness;
    }

    private void run(int producer, ProducerWorkload workload, Logger logger) {
        try {
            if (!options.virtualPerRequest()) workload.prepareThread();
            for (int cycle = 0; cycle < Math.max(200, 10_000 / options.producers()); cycle++) {
                call((producer + cycle * options.producers()) % options.events(), workload, logger, false);
            }
            warmed.countDown();
            start.await();
            if (phase != Phase.MEASUREMENT) return;
            for (int index = producer; index < options.events(); index += options.producers()) {
                long scheduled = options.rate() == 0 ? 0 : startedAt + index * 1_000_000_000L / options.rate();
                long wait;
                while (scheduled != 0 && (wait = scheduled - System.nanoTime()) > 0) {
                    requireRunning();
                    LockSupport.parkNanos(wait);
                }
                arrivals[index] = scheduled == 0 ? System.nanoTime() : scheduled;
                call(index, workload, logger, true);
            }
            done.countDown();
            finish.await();
        } catch (Throwable error) {
            if (!(error instanceof InterruptedException && phase == Phase.CLOSED)) {
                failure.compareAndSet(null, error);
            }
        } finally {
            MDC.clear();
            warmed.countDown();
            done.countDown();
        }
    }

    private void call(int index, ProducerWorkload workload, Logger logger, boolean measured) throws InterruptedException {
        requireRunning();
        if (!options.virtualPerRequest()) {
            invoke(index, workload, logger, measured);
            return;
        }
        // Each dispatcher joins its request before admitting another: at most producers() requests are live.
        Thread request = Thread.ofVirtual().name("comparison-request").start(() -> {
            try {
                workload.prepareThread();
                invoke(index, workload, logger, measured);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                MDC.clear();
            }
        });
        try {
            request.join();
            requireHealthy();
        } finally {
            if (request.isAlive()) request.interrupt();
        }
    }

    private void invoke(int index, ProducerWorkload workload, Logger logger, boolean measured) {
        long calledAt = measured ? System.nanoTime() : 0;
        if (measured && options.rate() != 0) lateness[index] = calledAt - arrivals[index];
        workload.log(logger, index);
        if (measured) callerTimes[index] = System.nanoTime() - calledAt;
    }

    private void requireHealthy() {
        if (failure.get() != null) throw new IllegalStateException("producer failed", failure.get());
    }

    private void requireRunning() throws InterruptedException {
        if (phase == Phase.CLOSED || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("producer was canceled");
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }

    @Override
    public void close() throws InterruptedException {
        phase = Phase.CLOSED;
        start.countDown();
        finish.countDown();
        for (Thread thread : threads) thread.interrupt();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (Thread thread : threads) {
            long remaining = deadline - System.nanoTime();
            // This overload waits for isAlive() to become false, including native thread teardown.
            if (remaining > 0) thread.join(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            require(!thread.isAlive(), "producer did not terminate: " + thread.getName());
        }
        requireHealthy();
    }
}
