package com.logyard4j.logyard.benchmarks.delivery;

import com.logyard4j.logyard.api.delivery.OverflowAction;
import com.logyard4j.logyard.benchmarks.fixture.DeliveryEvidence;
import com.logyard4j.logyard.benchmarks.fixture.SaturatedAsyncOutput;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** One SYNC overflow call, including a one-millisecond stalled delegate and lock handoff. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, batchSize = 1)
@Measurement(iterations = 5, batchSize = 1)
@Threads(1)
@Fork(1)
public class SynchronousOverflowBenchmark {
    private SaturatedAsyncOutput output;
    private Thread release;
    private int iteration;

    @Setup(org.openjdk.jmh.annotations.Level.Iteration)
    public void setUp() throws InterruptedException {
        output = new SaturatedAsyncOutput(OverflowAction.SYNC);
        iteration++;
        release = Thread.ofPlatform().daemon().name("benchmark-overflow-release").start(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (output.synchronousFallbacks() == 0 && System.nanoTime() < deadline) {
                LockSupport.parkNanos(10_000);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            output.release();
        });
    }

    @Benchmark
    public void synchronousFallback() {
        output.accept();
    }

    @TearDown(org.openjdk.jmh.annotations.Level.Iteration)
    public void tearDown(BenchmarkParams benchmark, IterationParams parameters) throws InterruptedException, IOException {
        try {
            output.verify(benchmark, parameters, iteration);
            DeliveryEvidence.require(output.synchronousFallbacks() == 1, "SYNC fixture requires one call per iteration");
        } finally {
            output.release();
            release.join(5_000);
        }
        DeliveryEvidence.require(!release.isAlive(), "SYNC release helper did not terminate");
    }
}
