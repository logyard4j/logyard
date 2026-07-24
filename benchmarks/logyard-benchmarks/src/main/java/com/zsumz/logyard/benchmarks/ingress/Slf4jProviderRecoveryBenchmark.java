package com.zsumz.logyard.benchmarks.ingress;

import com.zsumz.logyard.api.Logyard;
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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.slf4j.Logger;

import java.nio.file.Path;

/** Provider recovery after a process-global managed-runtime transition. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@Warmup(iterations = 1)
@Measurement(iterations = 5)
@Fork(1)
public class Slf4jProviderRecoveryBenchmark {
    private final LogyardConfigurationSource source = configuration();
    private RuntimeBundle application;
    private LogyardServiceProvider provider;
    private Logger logger;

    @Setup
    public void setUp() {
        application = LogyardBootstrap.acquire(RuntimeOwner.APPLICATION, source);
        provider = new LogyardServiceProvider();
        provider.initialize();
        logger = provider.getLoggerFactory().getLogger("benchmark.Slf4jProviderRecovery");
        logger.info("warm provider");
    }

    @TearDown
    public void tearDown() {
        provider.close();
        application.close();
        Logyard.shutdown();
    }

    @Benchmark
    public long recoverAfterManagedShutdown() {
        Logyard.shutdown();
        application = acquireAfterRetirement();
        logger.info("recovered provider {}", 42L);
        return BenchmarkOutputProvider.delivered();
    }

    private RuntimeBundle acquireAfterRetirement() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5L);
        while (true) {
            try {
                return LogyardBootstrap.acquire(RuntimeOwner.APPLICATION, source);
            } catch (com.zsumz.logyard.runtime.installation.RuntimeTransitionInProgressException transition) {
                if (System.nanoTime() >= deadline) {
                    throw transition;
                }
                Thread.onSpinWait();
            }
        }
    }

    private static LogyardConfigurationSource configuration() {
        return LogyardConfigurationSource.text(
                "provider recovery benchmark",
                """
                        schema = 1
                        [runtime]
                        watch = false
                        internal_status = "off"
                        [delivery]
                        mode = "async"
                        capacity = 1024
                        [loggers]
                        root = { level = "info", outputs = ["capture"] }
                        [outputs.capture]
                        type = "custom"
                        provider = "benchmark"
                        implementation = "com.zsumz.logyard.benchmarks.fixture.BenchmarkOutputProvider"
                        """,
                Path.of("."));
    }
}
