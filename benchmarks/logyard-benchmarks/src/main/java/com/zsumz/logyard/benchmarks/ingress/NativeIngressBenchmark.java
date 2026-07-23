package com.zsumz.logyard.benchmarks.ingress;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.benchmarks.fixture.BenchmarkFixtures;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
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

import java.util.concurrent.TimeUnit;

/** Native ingress costs across disabled, arity, fanout, and producer-count scenarios. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class NativeIngressBenchmark {
    private static final Object[] VARARGS = {42L, "customer-7", true, 9.5d};

    private DefaultLogyardRuntime disabledRuntime;
    private DefaultLogyardRuntime enabledRuntime;
    private DefaultLogyardRuntime fanoutRuntime;
    private LogyardLogger disabled;
    private LogyardLogger enabled;
    private LogyardLogger fanout;

    @Setup
    public void setUp() {
        disabledRuntime = BenchmarkFixtures.runtime(Level.ERROR, 1);
        enabledRuntime = BenchmarkFixtures.runtime(Level.INFO, 1);
        fanoutRuntime = BenchmarkFixtures.runtime(Level.INFO, 2);
        disabled = disabledRuntime.logger("benchmark.NativeDisabled");
        enabled = enabledRuntime.logger("benchmark.NativeEnabled");
        fanout = fanoutRuntime.logger("benchmark.NativeFanout");
    }

    @TearDown
    public void tearDown() {
        disabledRuntime.close();
        enabledRuntime.close();
        fanoutRuntime.close();
    }

    @Benchmark
    public void disabled() {
        disabled.info("ignored {}", 42L);
    }

    @Benchmark
    public void enabledZeroArguments() {
        enabled.info("accepted order");
    }

    @Benchmark
    public void enabledOneArgument() {
        enabled.info("accepted order {}", 42L);
    }

    @Benchmark
    public void enabledTwoArguments() {
        enabled.info("accepted order {} for {}", 42L, "customer-7");
    }

    @Benchmark
    public void enabledVarargs() {
        enabled.info("event {} {} {} {}", VARARGS);
    }

    @Benchmark
    public void structuredFields() {
        enabled.atInfo().event("order.accepted").add("order.id", 42L).add("customer.id", "customer-7").log("accepted order");
    }

    @Benchmark
    public void oneOutput() {
        enabled.info("accepted order {}", 42L);
    }

    @Benchmark
    public void twoOutputFanout() {
        fanout.info("accepted order {}", 42L);
    }

    @Benchmark
    @Threads(1)
    public void oneProducer() {
        enabled.info("accepted order {}", 42L);
    }

    @Benchmark
    @Threads(4)
    public void fourProducers() {
        enabled.info("accepted order {}", 42L);
    }

    @Benchmark
    @Threads(16)
    public void sixteenProducers() {
        enabled.info("accepted order {}", 42L);
    }
}
