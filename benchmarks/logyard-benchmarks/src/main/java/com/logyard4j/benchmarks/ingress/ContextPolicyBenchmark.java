package com.logyard4j.benchmarks.ingress;

import com.logyard4j.runtime.context.ContextPolicySnapshot;
import com.logyard4j.slf4j.internal.context.ContextSnapshotPolicy;
import com.logyard4j.slf4j.internal.context.LogyardMdcAdapter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Fixed versus reload-aware empty-MDC policy lookup. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ContextPolicyBenchmark {
    private final LogyardMdcAdapter mdc = new LogyardMdcAdapter();
    private final ContextSnapshotPolicy fixed = new ContextSnapshotPolicy(List.of());
    private final AtomicReference<ContextPolicySnapshot> published = new AtomicReference<>(ContextPolicySnapshot.none());
    private final ContextSnapshotPolicy reloadAware = new ContextSnapshotPolicy(published::get);

    @Benchmark
    public Object fixedConfiguration() {
        return fixed.capture(mdc);
    }

    @Benchmark
    public Object reloadAwareConfiguration() {
        return reloadAware.capture(mdc);
    }
}
