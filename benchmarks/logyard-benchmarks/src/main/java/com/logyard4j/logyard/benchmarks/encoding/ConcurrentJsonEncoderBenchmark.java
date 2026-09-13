package com.logyard4j.logyard.benchmarks.encoding;

import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.benchmarks.fixture.BenchmarkFixtures;
import com.logyard4j.logyard.output.json.encoding.JsonEncoder;
import com.logyard4j.logyard.output.json.encoding.ResourceAttributes;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/** Throughput of the single built-in encoder instance shared by synchronous producers. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ConcurrentJsonEncoderBenchmark {
    private final LogEvent event = BenchmarkFixtures.event(AttributeSet.builder().put("order.id", 42L).build());
    private final JsonEncoder encoder = new JsonEncoder(ResourceAttributes.service("orders", "benchmark", "1"));

    @Benchmark
    @Threads(16)
    public String encodeOnSharedInstance() {
        return encoder.encode(event);
    }
}
