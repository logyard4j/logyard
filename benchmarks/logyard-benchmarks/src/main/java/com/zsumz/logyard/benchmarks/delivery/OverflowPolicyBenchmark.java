package com.zsumz.logyard.benchmarks.delivery;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.delivery.OverflowAction;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.benchmarks.fixture.BenchmarkFixtures;
import com.zsumz.logyard.core.delivery.async.AsyncSink;
import com.zsumz.logyard.core.delivery.async.OverflowPolicy;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.OutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Full-queue costs for every overflow action. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class OverflowPolicyBenchmark {
    private static final int CAPACITY = 16;

    private final LogEvent event = BenchmarkFixtures.event(AttributeSet.EMPTY);
    private PrintStream originalError;
    private PrintStream discardedError;
    private SaturatedSink drop;
    private SaturatedSink synchronous;
    private SaturatedSink standardError;
    private SaturatedSink block;

    @Setup
    public void setUp() throws InterruptedException {
        originalError = System.err;
        discardedError = new PrintStream(OutputStream.nullOutputStream());
        System.setErr(discardedError);
        drop = saturated(OverflowAction.DROP, Duration.ZERO);
        synchronous = saturated(OverflowAction.SYNC, Duration.ZERO);
        standardError = saturated(OverflowAction.STDERR, Duration.ZERO);
        block = saturated(OverflowAction.BLOCK, Duration.ofNanos(1));
    }

    @TearDown
    public void tearDown() {
        drop.close();
        synchronous.close();
        standardError.close();
        block.close();
        System.setErr(originalError);
        discardedError.close();
    }

    @Benchmark
    public void drop() {
        drop.sink().accept(event);
    }

    @Benchmark
    public void synchronousFallback() {
        synchronous.sink().accept(event);
    }

    @Benchmark
    public void standardErrorFallback() {
        standardError.sink().accept(event);
    }

    @Benchmark
    public void boundedBlock() {
        block.sink().accept(event);
    }

    private SaturatedSink saturated(OverflowAction action, Duration wait) throws InterruptedException {
        SlowWorkerSink delegate = new SlowWorkerSink();
        AsyncSink sink = new AsyncSink(
                "benchmark-" + action.name().toLowerCase(java.util.Locale.ROOT),
                delegate,
                CAPACITY,
                new OverflowPolicy(Map.of(Level.INFO, new OverflowPolicy.Rule(action, wait))),
                Duration.ofSeconds(5));
        sink.accept(event);
        if (!delegate.workerEntered.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("async benchmark worker did not start");
        }
        for (int index = 0; index < CAPACITY; index++) {
            sink.accept(event);
        }
        return new SaturatedSink(sink);
    }

    private record SaturatedSink(AsyncSink sink) {
        void close() {
            sink.close();
        }
    }

    private static final class SlowWorkerSink implements EventSink {
        private final CountDownLatch workerEntered = new CountDownLatch(1);

        @Override
        public void accept(LogEvent event) {
            if (!Thread.currentThread().getName().startsWith("logyard-output-")) {
                return;
            }
            workerEntered.countDown();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }
}
