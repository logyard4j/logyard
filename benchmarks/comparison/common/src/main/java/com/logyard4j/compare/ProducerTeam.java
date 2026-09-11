package com.logyard4j.compare;

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

/** Keeps warmed producers alive through the drain snapshot and uses scheduled arrivals under overload. */
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

    public ProducerTeam(RunOptions options, Workload workload, Logger logger) {
        this.options = options;
        warmed = new CountDownLatch(options.producers());
        done = new CountDownLatch(options.producers());
        arrivals = new long[options.events()];
        callerTimes = new long[options.events()];
        lateness = new long[options.events()];
        for (int index = 0; index < options.producers(); index++) {
            int producer = index;
            Thread.Builder builder = options.virtualThreads() ? Thread.ofVirtual() : Thread.ofPlatform();
            threads.add(builder.name("comparison-producer-" + index).start(() -> run(producer, workload, logger)));
        }
    }

    public void awaitWarmup() throws InterruptedException {
        require(warmed.await(30, TimeUnit.SECONDS), "producer warmup timed out");
        requireHealthy();
    }

    public long begin() {
        startedAt = System.nanoTime();
        phase = Phase.MEASUREMENT;
        start.countDown();
        return startedAt;
    }

    public void awaitCalls() throws InterruptedException {
        require(done.await(90, TimeUnit.SECONDS), "producer calls timed out");
        requireHealthy();
    }

    public Set<Long> threadIds() {
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

    private void run(int producer, Workload workload, Logger logger) {
        try {
            workload.prepareThread();
            for (int cycle = 0; cycle < Math.max(200, 10_000 / options.producers()); cycle++) {
                workload.log(logger, (producer + cycle * options.producers()) % options.events());
            }
            warmed.countDown();
            start.await();
            if (phase != Phase.MEASUREMENT) return;
            for (int index = producer; index < options.events(); index += options.producers()) {
                long scheduled = options.rate() == 0 ? 0 : startedAt + index * 1_000_000_000L / options.rate();
                long wait;
                while (scheduled != 0 && (wait = scheduled - System.nanoTime()) > 0) LockSupport.parkNanos(wait);
                long calledAt = System.nanoTime();
                arrivals[index] = scheduled == 0 ? calledAt : scheduled;
                lateness[index] = scheduled == 0 ? 0 : calledAt - scheduled;
                workload.log(logger, index);
                callerTimes[index] = System.nanoTime() - calledAt;
            }
            done.countDown();
            finish.await();
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
        } finally {
            MDC.clear();
            warmed.countDown();
            done.countDown();
        }
    }

    private void requireHealthy() {
        if (failure.get() != null) throw new IllegalStateException("producer failed", failure.get());
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }

    @Override
    public void close() throws InterruptedException {
        phase = Phase.CLOSED;
        start.countDown();
        finish.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
            require(!thread.isAlive(), "producer did not terminate");
        }
        requireHealthy();
    }
}
