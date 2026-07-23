package com.zsumz.logyard.runtime.assembly.output;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.api.spi.HealthContributor;
import com.zsumz.logyard.api.spi.ProviderConfiguration;
import com.zsumz.logyard.config.CustomOutputConfig;
import com.zsumz.logyard.config.DeliveryConfig;
import com.zsumz.logyard.config.DeliveryOverrideConfig;
import com.zsumz.logyard.config.ProviderReferenceConfig;

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
