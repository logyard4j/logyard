package com.logyard4j.benchmarks.ingress;

import com.logyard4j.api.Logyard;
import com.logyard4j.benchmarks.fixture.BenchmarkOutputProvider;
import com.logyard4j.runtime.bootstrap.LogyardBootstrap;
import com.logyard4j.runtime.bootstrap.LogyardConfigurationSource;
import com.logyard4j.runtime.bootstrap.RuntimeBundle;
import com.logyard4j.runtime.bootstrap.RuntimeOwner;
import com.logyard4j.slf4j.LogyardServiceProvider;
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
            } catch (com.logyard4j.runtime.installation.process.RuntimeTransitionInProgressException transition) {
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
                        implementation = "com.logyard4j.benchmarks.fixture.BenchmarkOutputProvider"
                        """,
                Path.of("."));
    }
}
