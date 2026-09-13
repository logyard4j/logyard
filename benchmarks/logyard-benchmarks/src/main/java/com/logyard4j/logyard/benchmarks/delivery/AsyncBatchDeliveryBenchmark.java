package com.logyard4j.logyard.benchmarks.delivery;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.delivery.OverflowAction;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.output.BatchEventSink;
import com.logyard4j.logyard.benchmarks.fixture.BenchmarkFixtures;
import com.logyard4j.logyard.benchmarks.fixture.DeliveryEvidence;
import com.logyard4j.logyard.core.delivery.async.AsyncSink;
import com.logyard4j.logyard.core.delivery.async.OverflowPolicy;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** Reused-event admission to a batch delegate, with bounded blocking and complete drain accounting. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AsyncBatchDeliveryBenchmark {
    @Param({"1", "32", "256"})
    public int maximumBatchSize;

    private final LogEvent event = BenchmarkFixtures.event(AttributeSet.EMPTY);
    private final LongAdder attempts = new LongAdder();
    private AsyncSink sink;
    private CountingBatchSink delegate;
    private int iteration;

    @Setup(org.openjdk.jmh.annotations.Level.Iteration)
    public void setUp() {
        attempts.reset();
        iteration++;
        delegate = new CountingBatchSink();
        sink = new AsyncSink("batch-benchmark", delegate, 65_536,
                new OverflowPolicy(Map.of(Level.INFO,
                        new OverflowPolicy.Rule(OverflowAction.BLOCK, Duration.ofSeconds(5)))),
                Duration.ofSeconds(5));
    }

    @TearDown(org.openjdk.jmh.annotations.Level.Iteration)
    public void tearDown(BenchmarkParams benchmark, IterationParams parameters) throws IOException {
        sink.close();
        DeliveryEvidence.require(sink.emergencyFallbacks() == 0, "unexpected timeout or delegate failure");
        DeliveryEvidence.require(sink.dropped(Level.INFO) == 0, "batch fixture must deliver every attempt");
        DeliveryEvidence.async(benchmark, parameters, iteration, "batch-admission", attempts.sum(), 0,
                sink, delegate.observed, Map.of("batches", delegate.batches,
                        "singleton_batches", delegate.singletons, "largest_batch", delegate.largest));
    }

    @Benchmark
    @Threads(1)
    public void oneProducer() {
        attempt();
    }

    @Benchmark
    @Threads(16)
    public void sixteenProducers() {
        attempt();
    }

    private void attempt() {
        attempts.increment();
        sink.accept(event);
    }

    private final class CountingBatchSink implements BatchEventSink {
        private long observed;
        private long batches;
        private long singletons;
        private long largest;

        @Override
        public int maximumBatchSize() {
            return maximumBatchSize;
        }

        @Override
        public Duration maximumBatchDelay() {
            return Duration.ZERO;
        }

        @Override
        public void acceptBatch(List<LogEvent> events) {
            DeliveryEvidence.require(!events.isEmpty() && events.size() <= maximumBatchSize, "invalid batch size");
            for (LogEvent delivered : events) {
                if (delivered == event) observed++;
            }
            batches++;
            if (events.size() == 1) singletons++;
            largest = Math.max(largest, events.size());
        }
    }
}
