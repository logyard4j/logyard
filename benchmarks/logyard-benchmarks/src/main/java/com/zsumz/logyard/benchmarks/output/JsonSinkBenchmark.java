package com.zsumz.logyard.benchmarks.output;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.benchmarks.fixture.BenchmarkFixtures;
import com.zsumz.logyard.benchmarks.fixture.DeliveryEvidence;
import com.zsumz.logyard.benchmarks.fixture.FileDeliveryEvidence;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.output.json.encoding.JsonEncoder;
import com.zsumz.logyard.output.json.encoding.ResourceAttributes;
import com.zsumz.logyard.output.json.file.JsonFileSink;
import com.zsumz.logyard.output.json.stream.JsonLinesSink;
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
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;

import java.io.IOException;
import java.io.Writer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Buffered caller costs; close-time record reconciliation is outside the timed loop. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class JsonSinkBenchmark {
    private final LogEvent event = BenchmarkFixtures.event(AttributeSet.builder().put("order.id", 42L).build());
    private CountingWriter writer;
    private JsonLinesSink directStream;
    private JsonFileSink directFile;
    private DefaultLogyardRuntime runtime;
    private LogyardLogger logger;
    private FileDeliveryEvidence file;
    private long calls;
    private int sequence;

    @Setup(org.openjdk.jmh.annotations.Level.Iteration)
    public void setUp(BenchmarkParams benchmark) throws IOException {
        calls = 0;
        sequence++;
        JsonEncoder encoder = new JsonEncoder(ResourceAttributes.service("orders", "benchmark", "1"));
        boolean fileOutput = benchmark.getBenchmark().contains("File");
        if (fileOutput) {
            file = new FileDeliveryEvidence();
            directFile = new JsonFileSink(file.path(), benchmark.getBenchmark().endsWith("Mechanics")
                    ? ignored -> "{}" : encoder, 256 * 1_024, Duration.ofSeconds(1), false, null);
        } else {
            writer = new CountingWriter();
            directStream = new JsonLinesSink(writer, encoder, Duration.ofSeconds(1), false);
        }
        if (benchmark.getBenchmark().contains("synchronousRuntime")) {
            runtime = BenchmarkFixtures.runtime(Level.INFO, 1, fileOutput ? directFile : directStream);
            logger = runtime.logger("benchmark.SyncJson");
        }
    }

    @TearDown(org.openjdk.jmh.annotations.Level.Iteration)
    public void tearDown(BenchmarkParams benchmark, IterationParams iteration) throws IOException {
        if (runtime != null) runtime.close();
        if (directStream != null) directStream.close();
        if (directFile != null) directFile.close();
        if (file != null) {
            try {
                file.verify(benchmark, iteration, sequence, calls);
            } finally {
                file.close();
            }
        } else {
            DeliveryEvidence.require(writer.records == calls, "stream record count does not match benchmark calls");
            DeliveryEvidence.write(benchmark, iteration, sequence, "counting-writer", Map.of(
                    "benchmark_calls", calls, "sink_written", writer.records, "characters", writer.characters));
        }
    }

    @Benchmark
    @Threads(1)
    public long directStream() {
        calls++;
        directStream.accept(event);
        return writer.characters;
    }

    @Benchmark
    @Threads(1)
    public LogEvent directFile() {
        calls++;
        directFile.accept(event);
        return event;
    }

    @Benchmark
    @Threads(1)
    public LogEvent directFileMechanics() {
        calls++;
        directFile.accept(event);
        return event;
    }

    @Benchmark
    @Threads(1)
    public long synchronousRuntimeFile() {
        calls++;
        logger.info("accepted order {} for {}", 42L, "customer-7");
        return calls;
    }

    @Benchmark
    @Threads(1)
    public long synchronousRuntimeJson() {
        calls++;
        logger.info("accepted order {} for {}", 42L, "customer-7");
        return writer.characters;
    }

    private static final class CountingWriter extends Writer {
        private long characters;
        private long records;

        @Override
        public void write(char[] value, int offset, int length) {
            characters += length;
            for (int index = offset; index < offset + length; index++) {
                if (value[index] == '\n') records++;
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
