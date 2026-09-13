package com.logyard4j.logyard.tests.extensions;

import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.api.spi.output.OutputProvider;
import com.logyard4j.logyard.api.spi.output.OutputProviderContext;
import com.logyard4j.logyard.api.spi.config.ProviderConfiguration;
import com.logyard4j.logyard.api.spi.config.ProviderConfigurationSpec;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory output fixture for integration tests. */
public final class TestOutputProvider implements OutputProvider {
    private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>();
    private static final AtomicLong DELIVERED = new AtomicLong();

    @Override
    public String name() {
        return "test-output";
    }

    @Override
    public ProviderConfigurationSpec configurationSpec() {
        return ProviderConfigurationSpec.of(Set.of("marker"), Set.of("marker"));
    }

    @Override
    public EventSink create(
            OutputProviderContext context,
            ProviderConfiguration configuration) {
        String marker = configuration.requiredString("marker");
        if (context.formatter() == null || context.encoder() == null) {
            throw new IllegalArgumentException("test output requires formatter and encoder");
        }
        return new EventSink() {
            @Override
            public void accept(LogEvent event) {
                long delivered = DELIVERED.incrementAndGet();
                SNAPSHOT.set(new Snapshot(
                        marker,
                        context.outputName(),
                        context.resourceAttributes().get("service.name"),
                        context.formatter().format(event),
                        context.encoder().encode(event),
                        event.attributes().get("verified"),
                        Thread.currentThread().getName(),
                        delivered));
            }
        };
    }

    public static void reset() {
        DELIVERED.set(0L);
        SNAPSHOT.set(null);
    }

    public static Snapshot snapshot() {
        return Objects.requireNonNull(SNAPSHOT.get(), "test output did not receive an event");
    }

    public static long deliveredCount() {
        return DELIVERED.get();
    }

    public record Snapshot(
            String marker,
            String outputName,
            Object serviceName,
            String formatted,
            String encoded,
            Object enriched,
            String threadName,
            long deliveredCount) {
    }
}
