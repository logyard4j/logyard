package com.logyard4j.logyard.benchmarks.ingress;

import com.logyard4j.logyard.benchmarks.fixture.BenchmarkOutputProvider;
import com.logyard4j.logyard.runtime.bootstrap.LogyardBootstrap;
import com.logyard4j.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.logyard4j.logyard.runtime.bootstrap.RuntimeBundle;
import com.logyard4j.logyard.runtime.bootstrap.RuntimeOwner;
import com.logyard4j.logyard.slf4j.LogyardServiceProvider;
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

/** Cold provider logger call, including the first lazy adapter lease resolution. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@Warmup(iterations = 1)
@Measurement(iterations = 5)
@Fork(1)
public class Slf4jProviderFirstCallBenchmark {
    private RuntimeBundle application;

    @Setup
    public void setUp() {
        application = LogyardBootstrap.acquire(RuntimeOwner.APPLICATION, configuration());
    }

    @TearDown
    public void tearDown() {
        application.close();
    }

    @Benchmark
    public long firstCall() {
        try (LogyardServiceProvider provider = new LogyardServiceProvider()) {
            provider.initialize();
            Logger logger = provider.getLoggerFactory().getLogger("benchmark.Slf4jProviderFirstCall");
            logger.info("first provider call {}", 42L);
            return BenchmarkOutputProvider.delivered();
        }
    }

    private static LogyardConfigurationSource configuration() {
        return LogyardConfigurationSource.text(
                "provider first-call benchmark",
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
                        implementation = "com.logyard4j.logyard.benchmarks.fixture.BenchmarkOutputProvider"
                        """,
                Path.of("."));
    }
}
