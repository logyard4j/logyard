package com.zsumz.logyard.benchmarks.ingress;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.benchmarks.fixture.BenchmarkFixtures;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.slf4j.internal.context.ContextSnapshotPolicy;
import com.zsumz.logyard.slf4j.internal.context.LogyardMdcAdapter;
import com.zsumz.logyard.slf4j.internal.event.Slf4jEventMapper;
import com.zsumz.logyard.slf4j.internal.factory.LogyardLoggerFactory;
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
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** SLF4J ingress costs with disabled logging and empty or populated MDC. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class Slf4jIngressBenchmark {
    private DefaultLogyardRuntime disabledRuntime;
    private DefaultLogyardRuntime enabledRuntime;
    private Logger disabled;
    private Logger emptyMdc;
    private Logger populatedMdc;
    private BenchmarkFixtures.ObservingSink enabledSink;

    @Setup
    public void setUp() {
        disabledRuntime = BenchmarkFixtures.runtime(Level.ERROR, 1);
        BenchmarkFixtures.ObservedRuntime observed = BenchmarkFixtures.observedRuntime(Level.INFO, 1);
        enabledRuntime = observed.runtime();
        enabledSink = observed.sink();
        LogyardMdcAdapter mdc = new LogyardMdcAdapter();
        mdc.put("request.id", "request-7");
        mdc.put("tenant.id", "tenant-3");
        disabled = logger(disabledRuntime, new LogyardMdcAdapter(), List.of());
        emptyMdc = logger(enabledRuntime, new LogyardMdcAdapter(), List.of());
        populatedMdc = logger(enabledRuntime, mdc, List.of("request.id", "tenant.id"));
    }

    @TearDown
    public void tearDown() {
        disabledRuntime.close();
        enabledRuntime.close();
    }

    @Benchmark
    public void disabled() {
        disabled.info("ignored {}", 42L);
    }

    @Benchmark
    public LogEvent enabledEmptyMdc() {
        emptyMdc.info("accepted order {}", 42L);
        return enabledSink.last();
    }

    @Benchmark
    public LogEvent enabledPopulatedMdc() {
        populatedMdc.info("accepted order {}", 42L);
        return enabledSink.last();
    }

    @Benchmark
    public LogEvent structuredFieldsAndMdc() {
        populatedMdc.atInfo().addKeyValue("order.id", 42L).addKeyValue("customer.id", "customer-7").log("accepted order");
        return enabledSink.last();
    }

    private static Logger logger(DefaultLogyardRuntime runtime, LogyardMdcAdapter mdc, List<String> includedKeys) {
        return new LogyardLoggerFactory(
                runtime,
                new Slf4jEventMapper(mdc, new ContextSnapshotPolicy(includedKeys)))
                .getLogger("benchmark.Slf4j");
    }
}
