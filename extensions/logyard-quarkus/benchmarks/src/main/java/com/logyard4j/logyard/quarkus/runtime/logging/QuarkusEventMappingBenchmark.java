package com.logyard4j.logyard.quarkus.runtime.logging;

import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
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
    private ObservingSink sink;
    private ExtLogRecord copiedRecord;

    /** Creates the JMH state. */
    public QuarkusEventMappingBenchmark() {
    }

    /** Creates the runtime and complete source record for one parameter value. */
    @Setup
    public void setUp() {
        sink = new ObservingSink();
        runtime = DefaultLogyardRuntime.consoleOnly(sink);
        copiedRecord = record();
        copiedRecord.copyAll();
    }

    private ExtLogRecord record() {
        ExtLogRecord record = new ExtLogRecord(
                java.util.logging.Level.INFO,
                "mapped {0}",
                ExtLogRecord.FormatStyle.MESSAGE_FORMAT,
                QuarkusEventMappingBenchmark.class.getName());
        record.setLoggerName("benchmark.Quarkus");
        record.setParameters(new Object[]{"event"});
        for (int index = 0; index < mdcEntries; index++) {
            record.putMdc("context." + index, "value-" + index);
        }
        return record;
    }

    /** Releases the benchmark runtime after the trial. */
    @TearDown
    public void tearDown() {
        runtime.close();
    }

    /**
     * Maps and publishes a newly constructed, not-previously-copied Quarkus record.
     *
     * @return event retained by the observable benchmark sink
     */
    @Benchmark
    public LogEvent mapFreshRecord() {
        mapper.publish(runtime, record());
        return sink.last();
    }

    /**
     * Measures mapping separately when JBoss Log Manager has already copied the record.
     *
     * @return event retained by the observable benchmark sink
     */
    @Benchmark
    public LogEvent mapCopiedRecord() {
        mapper.publish(runtime, copiedRecord);
        return sink.last();
    }

    private static final class ObservingSink implements EventSink {
        private volatile LogEvent last;

        @Override
        public void accept(LogEvent event) {
            last = event;
        }

        LogEvent last() {
            return last;
        }
    }
}
