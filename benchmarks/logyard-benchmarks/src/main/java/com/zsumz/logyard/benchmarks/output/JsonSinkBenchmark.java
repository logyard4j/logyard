package com.zsumz.logyard.benchmarks.output;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.benchmarks.fixture.BenchmarkFixtures;
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

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Direct stream/file sink and synchronous runtime JSON delivery costs. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class JsonSinkBenchmark {
    private final LogEvent event = BenchmarkFixtures.event(AttributeSet.builder().put("order.id", 42L).build());
    private CountingWriter directWriter;
    private CountingWriter runtimeWriter;
    private JsonLinesSink directStream;
    private JsonFileSink directFile;
    private DefaultLogyardRuntime runtime;
    private LogyardLogger logger;
    private Path directory;

    @Setup
    public void setUp() throws IOException {
        directWriter = new CountingWriter();
        runtimeWriter = new CountingWriter();
        directStream = new JsonLinesSink(
                directWriter,
                new JsonEncoder(ResourceAttributes.service("orders", "benchmark", "1")),
                Duration.ofMinutes(1L),
                false);
        JsonLinesSink runtimeSink = new JsonLinesSink(
                runtimeWriter,
                new JsonEncoder(ResourceAttributes.service("orders", "benchmark", "1")),
                Duration.ofMinutes(1L),
                false);
        runtime = BenchmarkFixtures.runtime(Level.INFO, 1, runtimeSink);
        logger = runtime.logger("benchmark.SyncJson");
        directory = Files.createTempDirectory("logyard-file-benchmark-");
        directFile = new JsonFileSink(
                directory.resolve("events.jsonl"), ignored -> "{}", 16 * 1_024 * 1_024, Duration.ofMinutes(1L), false, null);
    }

    @TearDown
    public void tearDown() throws IOException {
        directStream.close();
        runtime.close();
        directFile.close();
        Files.deleteIfExists(directory.resolve("events.jsonl"));
        Files.deleteIfExists(directory.resolve("events.jsonl.logyard.lock"));
        Files.deleteIfExists(directory);
    }

    @Benchmark
    @Threads(1)
    public long directStream() {
        directStream.accept(event);
        return directWriter.characters;
    }

    @Benchmark
    @Threads(1)
    public LogEvent directFile() {
        directFile.accept(event);
        return event;
    }

    @Benchmark
    @Threads(1)
    public long synchronousRuntimeJson() {
        logger.info("accepted order {} for {}", 42L, "customer-7");
        return runtimeWriter.characters;
    }

    private static final class CountingWriter extends Writer {
        private long characters;

        @Override
        public void write(char[] value, int offset, int length) {
            characters += length;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
