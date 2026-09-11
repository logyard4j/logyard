package com.logyard4j.benchmarks.fixture;

import com.logyard4j.api.Level;
import com.logyard4j.api.delivery.OverflowAction;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.core.delivery.async.AsyncSink;
import com.logyard4j.core.delivery.async.OverflowPolicy;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** A full queue behind a latched delegate, with a hard fixture deadline and reconciled drain. */
public final class SaturatedAsyncOutput implements AutoCloseable {
    private static final int CAPACITY = 16;
    private final LogEvent event = BenchmarkFixtures.event(AttributeSet.EMPTY);
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final LongAdder calls = new LongAdder();
    private final LongAdder observed = new LongAdder();
    private final AsyncSink sink;
    private final OverflowAction action;

    public SaturatedAsyncOutput(OverflowAction action) throws InterruptedException {
        this.action = action;
        sink = new AsyncSink("saturated-benchmark", delivered -> {
            entered.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("saturation fixture exceeded its 30-second iteration deadline");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            if (delivered == event) observed.increment();
        }, CAPACITY, new OverflowPolicy(Map.of(Level.INFO,
                new OverflowPolicy.Rule(action, action == OverflowAction.BLOCK || action == OverflowAction.WAIT_DROP
                        ? Duration.ofNanos(1) : Duration.ZERO))),
                Duration.ofSeconds(5));
        try {
            sink.accept(event);
            DeliveryEvidence.require(entered.await(5, TimeUnit.SECONDS), "saturation worker did not start");
            for (int index = 0; index < CAPACITY; index++) sink.accept(event);
            DeliveryEvidence.require(sink.queued() == CAPACITY, "fixture queue is not full");
        } catch (RuntimeException | InterruptedException failure) {
            close();
            throw failure;
        }
    }

    public void accept() {
        calls.increment();
        sink.accept(event);
    }

    public long synchronousFallbacks() {
        return sink.synchronousFallbacks();
    }

    public void release() {
        release.countDown();
    }

    public void verify(BenchmarkParams benchmark, IterationParams iteration, int sequence) throws IOException {
        try {
            if (action != OverflowAction.SYNC) {
                DeliveryEvidence.require(sink.queued() == CAPACITY, "queue drained during the saturation measurement");
            }
        } finally {
            close();
        }
        DeliveryEvidence.require(sink.queuedEvents() == CAPACITY + 1, "a measured call bypassed the full-queue branch");
        long outcomes = switch (action) {
            case DROP, WAIT_DROP -> sink.dropped(Level.INFO);
            case SYNC -> sink.synchronousFallbacks();
            case BLOCK, STDERR -> sink.emergencyFallbacks();
        };
        DeliveryEvidence.require(outcomes == calls.sum(), "the selected overflow branch did not run for every call");
        DeliveryEvidence.async(benchmark, iteration, sequence, "overflow", calls.sum(), CAPACITY + 1, sink, observed.sum());
    }

    @Override
    public void close() {
        release();
        sink.close();
    }
}
