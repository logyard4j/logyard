package com.logyard4j.logyard.benchmarks.delivery;

import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.benchmarks.fixture.BenchmarkFixtures;
import com.logyard4j.logyard.benchmarks.fixture.DeliveryEvidence;
import com.logyard4j.logyard.core.delivery.async.AsyncSink;
import com.logyard4j.logyard.core.delivery.async.OverflowPolicy;
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
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** Admission attempts with a reused event; drain reconciliation is separate from the JMH score. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AsyncDeliveryBenchmark {
    private final LogEvent event = BenchmarkFixtures.event(AttributeSet.EMPTY);
    private AsyncSink sink;
    private final LongAdder attempts = new LongAdder();
    private final LongAdder observed = new LongAdder();
    private int iteration;

    @Setup(org.openjdk.jmh.annotations.Level.Iteration)
    public void setUp() {
        attempts.reset();
        observed.reset();
        iteration++;
        sink = new AsyncSink(
                "benchmark",
                delivered -> { if (delivered == event) observed.increment(); },
                65_536,
                new OverflowPolicy(Map.of()),
                Duration.ofSeconds(5));
    }

    @TearDown(org.openjdk.jmh.annotations.Level.Iteration)
    public void tearDown(BenchmarkParams benchmark, IterationParams parameters) throws IOException {
        sink.close();
        DeliveryEvidence.require(sink.emergencyFallbacks() == 0, "unexpected delegate failure");
        DeliveryEvidence.async(benchmark, parameters, iteration, "admission", attempts.sum(), 0, sink, observed.sum());
    }

    @Benchmark
    @Threads(1)
    public void oneProducer() {
        attempt();
    }

    @Benchmark
    @Threads(4)
    public void fourProducers() {
        attempt();
    }

    @Benchmark
    @Threads(16)
    public void sixteenProducers() {
        attempt();
    }

    @Benchmark
    @Threads(64)
    public void sixtyFourProducers() {
        attempt();
    }

    private void attempt() {
        attempts.increment();
        sink.accept(event);
    }
}
