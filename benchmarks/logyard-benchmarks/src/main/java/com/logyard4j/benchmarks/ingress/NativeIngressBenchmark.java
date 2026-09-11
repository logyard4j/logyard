package com.logyard4j.benchmarks.ingress;

import com.logyard4j.api.Level;
import com.logyard4j.api.LogyardLogger;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.benchmarks.fixture.BenchmarkFixtures;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;
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
    private DefaultLogyardRuntime elidableRuntime;
    private LogyardLogger disabled;
    private LogyardLogger enabled;
    private LogyardLogger fanout;
    private LogyardLogger elidable;
    private BenchmarkFixtures.ObservingSink enabledSink;
    private BenchmarkFixtures.ObservingSink fanoutSink;

    @Setup
    public void setUp() {
        disabledRuntime = BenchmarkFixtures.runtime(Level.ERROR, 1);
        BenchmarkFixtures.ObservedRuntime observedEnabled = BenchmarkFixtures.observedRuntime(Level.INFO, 1);
        BenchmarkFixtures.ObservedRuntime observedFanout = BenchmarkFixtures.observedRuntime(Level.INFO, 2);
        enabledRuntime = observedEnabled.runtime();
        enabledSink = observedEnabled.sink();
        fanoutRuntime = observedFanout.runtime();
        fanoutSink = observedFanout.sink();
        elidableRuntime = BenchmarkFixtures.runtime(Level.INFO, 1);
        disabled = disabledRuntime.logger("benchmark.NativeDisabled");
        enabled = enabledRuntime.logger("benchmark.NativeEnabled");
        fanout = fanoutRuntime.logger("benchmark.NativeFanout");
        elidable = elidableRuntime.logger("benchmark.NativeElidable");
    }

    @TearDown
    public void tearDown() {
        disabledRuntime.close();
        enabledRuntime.close();
        fanoutRuntime.close();
        elidableRuntime.close();
    }

    @Benchmark
    public void disabled() {
        disabled.info("ignored {}", 42L);
    }

    @Benchmark
    public LogEvent enabledZeroArguments() {
        enabled.info("accepted order");
        return enabledSink.last();
    }

    @Benchmark
    public LogEvent enabledOneArgument() {
        enabled.info("accepted order {}", 42L);
        return enabledSink.last();
    }

    @Benchmark
    public LogEvent enabledTwoArguments() {
        enabled.info("accepted order {} for {}", 42L, "customer-7");
        return enabledSink.last();
    }

    @Benchmark
    public LogEvent enabledVarargs() {
        enabled.info("event {} {} {} {}", VARARGS);
        return enabledSink.last();
    }

    @Benchmark
    public LogEvent structuredFields() {
        enabled.atInfo().event("order.accepted").add("order.id", 42L).add("customer.id", "customer-7").log("accepted order");
        return enabledSink.last();
    }

    @Benchmark
    public LogEvent oneOutput() {
        enabled.info("accepted order {}", 42L);
        return enabledSink.last();
    }

    @Benchmark
    public LogEvent twoOutputFanout() {
        fanout.info("accepted order {}", 42L);
        return fanoutSink.last();
    }

    @Benchmark
    public void pipelineWithElidableSink() {
        elidable.info("accepted order {}", 42L);
    }

    @Benchmark
    @Threads(1)
    public LogEvent oneProducer() {
        enabled.info("accepted order {}", 42L);
        return enabledSink.last();
    }

    @Benchmark
    @Threads(4)
    public LogEvent fourProducers() {
        enabled.info("accepted order {}", 42L);
        return enabledSink.last();
    }

    @Benchmark
    @Threads(16)
    public LogEvent sixteenProducers() {
        enabled.info("accepted order {}", 42L);
        return enabledSink.last();
    }
}
