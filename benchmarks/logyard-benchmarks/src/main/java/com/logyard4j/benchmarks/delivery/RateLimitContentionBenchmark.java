package com.logyard4j.benchmarks.delivery;

import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.benchmarks.fixture.BenchmarkFixtures;
import com.logyard4j.core.processing.RateLimitProcessor;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/** Global-monitor contention evidence for keyed rate limiting. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RateLimitContentionBenchmark {
    private final LogEvent event = BenchmarkFixtures.event(AttributeSet.EMPTY);
    private final RateLimitProcessor limiter = new RateLimitProcessor(1_000_000.0d, 1_000_000, "logger", 4_096);

    @Benchmark
    @Threads(4)
    public LogEvent fourProducers() {
        return limiter.process(event);
    }

    @Benchmark
    @Threads(16)
    public LogEvent sixteenProducers() {
        return limiter.process(event);
    }

    @Benchmark
    @Threads(64)
    public LogEvent sixtyFourProducers() {
        return limiter.process(event);
    }
}
