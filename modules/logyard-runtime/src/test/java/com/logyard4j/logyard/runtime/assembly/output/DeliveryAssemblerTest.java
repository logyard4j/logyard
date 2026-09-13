package com.logyard4j.logyard.runtime.assembly.output;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.diagnostics.ComponentHealth;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.api.spi.diagnostics.HealthContributor;
import com.logyard4j.logyard.api.spi.config.ProviderConfiguration;
import com.logyard4j.logyard.config.output.CustomOutputConfig;
import com.logyard4j.logyard.config.delivery.DeliveryConfig;
import com.logyard4j.logyard.config.delivery.DeliveryOverrideConfig;
import com.logyard4j.logyard.config.extension.ProviderReferenceConfig;

import java.time.Duration;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

final class DeliveryAssemblerTest {
    @Test
    void alwaysIsolatesProviderBackedOutputsFromCallerThreads() {
        CustomOutputConfig output = new CustomOutputConfig(
                "custom",
                Level.TRACE,
                new ProviderReferenceConfig("test", null, ProviderConfiguration.EMPTY),
                null,
                null,
                DeliveryOverrideConfig.INHERIT);
        DeliveryConfig synchronous =
                new DeliveryConfig("sync", DeliveryConfig.MIN_CAPACITY, new EnumMap<>(Level.class));
        EventSink sink = DeliveryAssembler.wrap(output, ignored -> { }, synchronous, Duration.ofSeconds(1));

        try {
            ComponentHealth health = ((HealthContributor) sink).health("custom");

            assertEquals("async", health.details().get("delivery"));
            assertEquals("false", health.details().get("caller_thread_delivery"));
            assertEquals((long) DeliveryConfig.MIN_CAPACITY, health.metrics().get("capacity"));
        } finally {
            sink.close();
        }
    }
}
