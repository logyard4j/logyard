package com.zsumz.logyard.benchmarks.delivery;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded asynchronous admission with one, four, and sixteen producers. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AsyncDeliveryBenchmark {
    private final LogEvent event = BenchmarkFixtures.event(AttributeSet.EMPTY);
    private AsyncSink sink;

    @Setup
    public void setUp() {
        sink = new AsyncSink(
                "benchmark",
                BenchmarkFixtures.DISCARDING_SINK,
                65_536,
                new OverflowPolicy(Map.of()),
                Duration.ofSeconds(5));
    }

    @TearDown
    public void tearDown() {
        sink.close();
    }

    @Benchmark
    @Threads(1)
    public void oneProducer() {
        sink.accept(event);
    }

    @Benchmark
    @Threads(4)
    public void fourProducers() {
        sink.accept(event);
    }

    @Benchmark
    @Threads(16)
    public void sixteenProducers() {
        sink.accept(event);
    }
}
