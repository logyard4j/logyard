package com.logyard4j.logyard.benchmarks.output;

import com.logyard4j.logyard.api.LogyardLogger;
import com.logyard4j.logyard.benchmarks.fixture.DeliveryEvidence;
import com.logyard4j.logyard.runtime.bootstrap.LogyardBootstrap;
import com.logyard4j.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.logyard4j.logyard.runtime.bootstrap.RuntimeBundle;
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
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Production process-stream encoding and buffering into an observable byte counter, without OS I/O. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
public class JsonProcessStreamBenchmark {
    private RuntimeBundle application;
    private LogyardLogger logger;
    private CountingStream bytes;
    private PrintStream processStream;
    private long calls;
    private int sequence;

    @Setup(org.openjdk.jmh.annotations.Level.Iteration)
    public void setUp(BenchmarkParams benchmark) {
        DeliveryEvidence.require(benchmark.getThreads() == 1, "process-stream fixture requires one producer");
        calls = 0;
        sequence++;
        bytes = new CountingStream();
        processStream = new PrintStream(bytes, false, StandardCharsets.UTF_8);
        PrintStream original = System.err;
        try {
            System.setErr(processStream);
            application = LogyardBootstrap.start(configuration());
        } catch (RuntimeException | Error failure) {
            processStream.close();
            throw failure;
        } finally {
            // The configured output owns its reference; JMH diagnostics retain normal stderr.
            System.setErr(original);
        }
        logger = application.runtime().logger("benchmark.ProcessJson");
    }

    @TearDown(org.openjdk.jmh.annotations.Level.Iteration)
    public void tearDown(BenchmarkParams benchmark, IterationParams iteration) throws IOException {
        try {
            application.close();
            DeliveryEvidence.require(bytes.closes == 0, "runtime closed the process-owned stream");
            DeliveryEvidence.require(!processStream.checkError(), "process stream reported an output failure");
            DeliveryEvidence.require(bytes.records == calls, "process-stream records do not match benchmark calls");
            DeliveryEvidence.write(benchmark, iteration, sequence, "counting-stream", Map.of(
                    "benchmark_calls", calls, "sink_written", bytes.records, "bytes", bytes.written));
        } finally {
            processStream.close();
        }
    }

    @Benchmark
    public long literal() {
        calls++;
        logger.info("accepted order");
        return calls;
    }

    @Benchmark
    public long arguments() {
        calls++;
        logger.info("accepted order {} for {}", 42L, "customer-7");
        return calls;
    }

    @Benchmark
    public long structured() {
        calls++;
        logger.atInfo().add("order.id", 42L).add("customer.id", "customer-7")
                .add("request.id", "request-9").log("accepted order");
        return calls;
    }

    private static LogyardConfigurationSource configuration() {
        return LogyardConfigurationSource.text("process stream benchmark", """
                schema = 1
                [service]
                name = "orders"
                environment = "benchmark"
                version = "1"
                [runtime]
                watch = false
                internal_status = "off"
                shutdown_timeout = "2s"
                [delivery]
                mode = "sync"
                [loggers]
                root = { level = "info", outputs = ["json"] }
                [outputs.json]
                type = "stream"
                stream = "stderr"
                flush = "1s"
                """, Path.of("."));
    }

    private static final class CountingStream extends OutputStream {
        private long written;
        private long records;
        private long closes;

        @Override
        public void write(int value) {
            written++;
            if (value == '\n') records++;
        }

        @Override
        public void write(byte[] value, int offset, int length) {
            written += length;
            for (int index = offset; index < offset + length; index++) {
                if (value[index] == '\n') records++;
            }
        }

        @Override
        public void close() {
            closes++;
        }
    }
}
