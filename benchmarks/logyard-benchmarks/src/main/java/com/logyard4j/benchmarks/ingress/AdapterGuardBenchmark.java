package com.logyard4j.benchmarks.ingress;

import com.logyard4j.runtime.adapter.AdapterReentryGuard;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/** Warm per-thread guard cost, without event capture, encoding, or delivery. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AdapterGuardBenchmark {
    private final AdapterReentryGuard guard = new AdapterReentryGuard();

    @Benchmark
    public boolean enterAndExit() {
        boolean entered = guard.enter();
        if (entered) {
            guard.exit();
        }
        return entered;
    }
}
