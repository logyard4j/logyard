package com.logyard4j.benchmarks.output;

import com.logyard4j.output.json.flush.FlushScheduler;
import com.logyard4j.output.json.flush.TimedFlushController;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Allocation and scheduling guard for records sharing one pending timed flush. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TimedFlushBenchmark {
    private CountingScheduler scheduler;
    private TimedFlushController controller;

    @Setup(Level.Iteration)
    public void preparePendingFlush() {
        scheduler = new CountingScheduler();
        controller = new TimedFlushController(Duration.ofMinutes(1L), scheduler, () -> {
        });
        controller.recordWritten();
    }

    @TearDown(Level.Iteration)
    public void verifySingleSchedule() {
        controller.close();
        if (scheduler.schedules != 1) {
            throw new IllegalStateException("dirty period scheduled " + scheduler.schedules + " flush tasks");
        }
    }

    @Benchmark
    public int recordWhileFlushPending() {
        controller.recordWritten();
        return scheduler.schedules;
    }

    private static final class CountingScheduler implements FlushScheduler {
        private int schedules;

        @Override
        public ScheduledFlush schedule(Duration delay, Runnable action) {
            schedules++;
            return () -> {
            };
        }
    }
}
