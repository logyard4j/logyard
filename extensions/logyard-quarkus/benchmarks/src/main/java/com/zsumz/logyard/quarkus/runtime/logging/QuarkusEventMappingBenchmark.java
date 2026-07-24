package com.zsumz.logyard.quarkus.runtime.logging;

import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import org.jboss.logmanager.ExtLogRecord;
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
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/** Complete JBoss Log Manager record mapping with representative MDC sizes. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class QuarkusEventMappingBenchmark {
    @Param({"0", "1", "4"})
    private int mdcEntries;

    private final QuarkusEventMapper mapper = new QuarkusEventMapper();
    private DefaultLogyardRuntime runtime;
    private ExtLogRecord record;

    /** Creates the JMH state. */
    public QuarkusEventMappingBenchmark() {
    }

    /** Creates the runtime and complete source record for one parameter value. */
    @Setup
    public void setUp() {
        runtime = DefaultLogyardRuntime.consoleOnly(event -> { });
        record = new ExtLogRecord(
                java.util.logging.Level.INFO,
                "mapped {0}",
                ExtLogRecord.FormatStyle.MESSAGE_FORMAT,
                QuarkusEventMappingBenchmark.class.getName());
        record.setLoggerName("benchmark.Quarkus");
        record.setParameters(new Object[]{"event"});
        for (int index = 0; index < mdcEntries; index++) {
            record.putMdc("context." + index, "value-" + index);
        }
    }

    /** Releases the benchmark runtime after the trial. */
    @TearDown
    public void tearDown() {
        runtime.close();
    }

    /** Maps and publishes one complete Quarkus record. */
    @Benchmark
    public void mapCompleteRecord() {
        mapper.publish(runtime, record);
    }
}
