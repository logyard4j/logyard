package com.zsumz.logyard.benchmarks.output;

import com.zsumz.logyard.output.json.flush.TimedFlushController;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** End-to-end deadline-to-virtual-thread dispatch throughput. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TimedFlushDispatchBenchmark {
    private CountDownLatch completed;
    private TimedFlushController controller;

    @Setup(Level.Invocation)
    public void setUp() {
        completed = new CountDownLatch(1);
        controller = new TimedFlushController(Duration.ofNanos(1L), completed::countDown);
    }

    @TearDown(Level.Invocation)
    public void tearDown() {
        controller.close();
    }

    @Benchmark
    public boolean dispatchAndComplete() throws InterruptedException {
        controller.recordWritten();
        return completed.await(2L, TimeUnit.SECONDS);
    }
}
