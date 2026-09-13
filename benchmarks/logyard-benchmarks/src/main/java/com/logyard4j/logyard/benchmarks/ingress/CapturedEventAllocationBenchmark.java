package com.logyard4j.logyard.benchmarks.ingress;

import com.logyard4j.logyard.api.LogyardLogger;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.benchmarks.fixture.BenchmarkFixtures;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
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

import java.util.concurrent.TimeUnit;

/** Escaped-event allocation for representative small, structured, and exception events. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CapturedEventAllocationBenchmark {
    private DefaultLogyardRuntime runtime;
    private LogyardLogger logger;
    private BenchmarkFixtures.ObservingSink sink;
    private IllegalStateException failure;

    /** Creates an observable output so each captured event escapes scalar replacement. */
    @Setup
    public void setUp() {
        BenchmarkFixtures.ObservedRuntime observed = BenchmarkFixtures.observedRuntime(com.logyard4j.logyard.api.Level.INFO, 1);
        runtime = observed.runtime();
        sink = observed.sink();
        logger = runtime.logger("benchmark.CapturedEvent");
        failure = new IllegalStateException("representative failure");
        StackTraceElement[] frames = new StackTraceElement[8];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = new StackTraceElement("com.example.orders.OrderService", "accept", "OrderService.java", 40 + index);
        }
        failure.setStackTrace(frames);
    }

    /** Releases the benchmark runtime. */
    @TearDown
    public void tearDown() {
        runtime.close();
    }

    /**
     * Captures a message-only event.
     *
     * @return escaped captured event
     */
    @Benchmark
    public LogEvent smallEvent() {
        logger.info("accepted");
        return sink.last();
    }

    /**
     * Captures representative identifiers and request context.
     *
     * @return escaped captured event
     */
    @Benchmark
    public LogEvent structuredEvent() {
        logger.atInfo()
                .event("order.accepted")
                .add("order.id", 42L)
                .add("customer.id", "customer-7")
                .add("request.id", "request-9")
                .log("accepted");
        return sink.last();
    }

    /**
     * Captures a positional argument through the fluent builder.
     *
     * @return escaped captured event
     */
    @Benchmark
    public LogEvent fluentArgument() {
        logger.atInfo().argument(42L).log("accepted {}");
        return sink.last();
    }

    /**
     * Captures a fixed eight-frame exception without JMH's own setup stack.
     *
     * @return escaped captured event
     */
    @Benchmark
    public LogEvent exceptionEvent() {
        logger.atError().cause(failure).log("failed");
        return sink.last();
    }
}
