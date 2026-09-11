package com.logyard4j.benchmarks.delivery;

import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.benchmarks.fixture.BenchmarkFixtures;
import com.logyard4j.core.delivery.CompositeSink;
import com.logyard4j.core.failure.ComponentInvocationException;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Healthy and isolated-failure fanout costs across representative sink counts. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CompositeSinkBenchmark {
    private final LogEvent event = BenchmarkFixtures.event(AttributeSet.EMPTY);
    private final EventSink failing = ignored -> {
        throw new IllegalStateException("expected benchmark failure");
    };

    @Param({"1", "2", "4"})
    private int sinkCount;

    private CompositeSink successful;
    private CompositeSink firstFailing;
    private CompositeSink lastFailing;
    private BenchmarkFixtures.ObservingSink observer;

    @Setup
    public void setUp() {
        observer = new BenchmarkFixtures.ObservingSink();
        successful = new CompositeSink(sinks(-1));
        firstFailing = new CompositeSink(sinks(0));
        lastFailing = new CompositeSink(sinks(sinkCount - 1));
    }

    @Benchmark
    public LogEvent success() {
        successful.accept(event);
        return observer.last();
    }

    @Benchmark
    public void firstFailure(Blackhole blackhole) {
        invokeFailing(firstFailing, blackhole);
    }

    @Benchmark
    public void lastFailure(Blackhole blackhole) {
        invokeFailing(lastFailing, blackhole);
    }

    private List<EventSink> sinks(int failingIndex) {
        List<EventSink> sinks = new ArrayList<>(sinkCount);
        for (int index = 0; index < sinkCount; index++) {
            sinks.add(index == failingIndex ? failing : observer);
        }
        return sinks;
    }

    private void invokeFailing(CompositeSink composite, Blackhole blackhole) {
        try {
            composite.accept(event);
        } catch (ComponentInvocationException expected) {
            blackhole.consume(expected);
        }
    }
}
