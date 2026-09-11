package com.zsumz.logyard.benchmarks.ingress;

import com.zsumz.logyard.benchmarks.fixture.BenchmarkOutputProvider;
import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;
import com.zsumz.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import com.zsumz.logyard.runtime.bootstrap.RuntimeOwner;
import com.zsumz.logyard.slf4j.LogyardServiceProvider;
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
import org.slf4j.Logger;
import org.slf4j.spi.MDCAdapter;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Actual SLF4J provider path, including lazy managed access and ServiceLoader output delivery. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class Slf4jProviderBenchmark {
    private RuntimeBundle application;
    private LogyardServiceProvider provider;
    private Logger logger;
    private final AtomicInteger supplierCalls = new AtomicInteger();
    private final Supplier<String> disabledArgument = () -> {
        supplierCalls.incrementAndGet();
        return "unexpected evaluation";
    };

    @Setup
    public void setUp() {
        application = LogyardBootstrap.acquire(RuntimeOwner.APPLICATION, configuration());
        provider = new LogyardServiceProvider();
        provider.initialize();
        logger = provider.getLoggerFactory().getLogger("benchmark.Slf4jProvider");
        logger.info("warm provider");
    }

    @TearDown
    public void tearDown() {
        provider.close();
        application.close();
        if (supplierCalls.get() != 0) {
            throw new IllegalStateException("disabled logging evaluated an argument supplier");
        }
    }

    @Benchmark
    public boolean disabled() {
        return logger.isDebugEnabled();
    }

    @Benchmark
    public void disabledClassic() {
        logger.debug("ignored {}", 42L);
    }

    @Benchmark
    public void disabledFluentSupplier() {
        logger.atDebug().addArgument(disabledArgument).log("ignored {}");
    }

    @Benchmark
    @Threads(1)
    public long enabledOneProducer() {
        logger.info("accepted order {}", 42L);
        return BenchmarkOutputProvider.delivered();
    }

    @Benchmark
    @Threads(4)
    public long enabledFourProducers() {
        logger.info("accepted order {}", 42L);
        return BenchmarkOutputProvider.delivered();
    }

    @Benchmark
    @Threads(16)
    public long enabledSixteenProducers() {
        logger.info("accepted order {}", 42L);
        return BenchmarkOutputProvider.delivered();
    }

    @Benchmark
    public long enabledPopulatedMdc(PopulatedMdc context) {
        logger.info("accepted order {}", context.orderId);
        return BenchmarkOutputProvider.delivered();
    }

    private static LogyardConfigurationSource configuration() {
        return LogyardConfigurationSource.text("provider benchmark", """
                schema = 1
                [runtime]
                watch = false
                internal_status = "off"
                shutdown_timeout = "2s"
                [delivery]
                mode = "async"
                capacity = 65536
                [context]
                mdc = ["request.id", "tenant.id"]
                [loggers]
                root = { level = "info", outputs = ["capture"] }
                [outputs.capture]
                type = "custom"
                provider = "benchmark"
                implementation = "com.zsumz.logyard.benchmarks.fixture.BenchmarkOutputProvider"
                """, Path.of("."));
    }

    /** Per-worker MDC state, using the same adapter exposed by the production provider. */
    @State(Scope.Thread)
    public static class PopulatedMdc {
        private long orderId = 42L;

        @Setup
        public void setUp(Slf4jProviderBenchmark benchmark) {
            MDCAdapter mdc = benchmark.provider.getMDCAdapter();
            mdc.put("request.id", "request-7");
            mdc.put("tenant.id", "tenant-3");
        }

        @TearDown
        public void tearDown(Slf4jProviderBenchmark benchmark) {
            benchmark.provider.getMDCAdapter().clear();
        }
    }
}
