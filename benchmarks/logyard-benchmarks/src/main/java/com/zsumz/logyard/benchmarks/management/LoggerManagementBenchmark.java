package com.zsumz.logyard.benchmarks.management;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.routing.RouteDefinition;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.core.runtime.RuntimePlan;
import com.zsumz.logyard.runtime.management.LoggerLevelManagement;
import com.zsumz.logyard.runtime.management.LoggerLevelSnapshot;
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
}
