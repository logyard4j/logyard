package com.logyard4j.benchmarks.delivery;

import com.logyard4j.api.delivery.OverflowAction;
import com.logyard4j.benchmarks.fixture.SaturatedAsyncOutput;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

/** Maintained full-queue branches; emergency formatting uses a null stderr destination. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class OverflowPolicyBenchmark {
    @Param({"DROP", "WAIT_DROP", "BLOCK", "STDERR"})
    public OverflowAction action;
    private PrintStream originalError;
    private PrintStream discardedError;
    private SaturatedAsyncOutput output;
    private int iteration;

    @Setup(org.openjdk.jmh.annotations.Level.Iteration)
    public void setUp() throws InterruptedException {
        originalError = System.err;
        discardedError = new PrintStream(OutputStream.nullOutputStream());
        System.setErr(discardedError);
        try {
            output = new SaturatedAsyncOutput(action);
            iteration++;
        } catch (RuntimeException | InterruptedException failure) {
            restoreError();
            throw failure;
        }
    }

    @TearDown(org.openjdk.jmh.annotations.Level.Iteration)
    public void tearDown(BenchmarkParams benchmark, IterationParams parameters) throws IOException {
        try {
            output.verify(benchmark, parameters, iteration);
        } finally {
            restoreError();
        }
    }

    @Benchmark
    public void overflow() {
        output.accept();
    }

    private void restoreError() {
        System.setErr(originalError);
        discardedError.close();
    }
}
