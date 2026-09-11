package com.logyard4j.benchmarks.management;

import com.logyard4j.api.Level;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.routing.RouteDefinition;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.core.runtime.RuntimePlan;
import com.logyard4j.runtime.management.LoggerLevelManagement;
import com.logyard4j.runtime.management.LoggerLevelSnapshot;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Complete logger-management listings across realistic large configurations. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class LoggerManagementBenchmark {
    @Param({"1000", "10000"})
    private int loggerCount;

    private DefaultLogyardRuntime runtime;
    private LoggerLevelManagement management;

    /** Creates one immutable runtime plan with the requested number of logger rules. */
    @Setup
    public void setUp() {
        Map<String, RouteDefinition> rules = new LinkedHashMap<>();
        for (int index = 0; index < loggerCount; index++) {
            rules.put("com.example.service." + index, new RouteDefinition(Level.DEBUG, null, null));
        }
        EventSink sink = ignored -> { };
        runtime = new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("capture"), List.of()),
                rules,
                Map.of("capture", sink),
                Map.of()));
        management = LoggerLevelManagement.forRuntime(runtime);
    }

    /** Releases the benchmark runtime. */
    @TearDown
    public void tearDown() {
        runtime.close();
    }

    /**
     * Lists every known logger from one immutable management snapshot.
     *
     * @return complete logger-level view
     */
    @Benchmark
    public Map<String, LoggerLevelSnapshot> listAllLevels() {
        return management.listLoggerLevels();
    }

    /**
     * Resolves one logger without materializing complete management maps or known-name sets.
     *
     * @return point logger-level view
     */
    @Benchmark
    public LoggerLevelSnapshot pointLevel() {
        return management.getLoggerLevel("com.example.service.500.child");
    }
}
