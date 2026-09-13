package com.logyard4j.logyard.benchmarks.fixture;

import com.logyard4j.logyard.api.spi.config.ProviderConfiguration;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.api.spi.output.OutputProvider;
import com.logyard4j.logyard.api.spi.output.OutputProviderContext;

import java.util.concurrent.atomic.LongAdder;

/** Nonblocking ServiceLoader output used to exercise the production provider path without I/O. */
public final class BenchmarkOutputProvider implements OutputProvider {
    private static final LongAdder DELIVERED = new LongAdder();

    @Override
    public String name() {
        return "benchmark";
    }

    @Override
    public EventSink create(OutputProviderContext context, ProviderConfiguration configuration) {
        return event -> DELIVERED.increment();
    }

    public static long delivered() {
        return DELIVERED.sum();
    }
}
