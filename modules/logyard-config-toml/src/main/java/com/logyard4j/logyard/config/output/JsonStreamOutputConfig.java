package com.logyard4j.logyard.config.output;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.config.delivery.DeliveryOverrideConfig;
import com.logyard4j.logyard.config.validation.ConfigNames;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Framed encoded records written to a process-owned standard stream. */
public record JsonStreamOutputConfig(
        String name,
        Level minimumLevel,
        String stream,
        Duration flushInterval,
        String encoder,
        DeliveryOverrideConfig delivery) implements OutputConfig {
    public JsonStreamOutputConfig {
        name = ConfigNames.component(name, "output name");
        Objects.requireNonNull(minimumLevel, "minimumLevel");
        stream = requireText(stream, "stream").toLowerCase(java.util.Locale.ROOT);
        Objects.requireNonNull(flushInterval, "flushInterval");
        encoder = ConfigNames.optionalReference(encoder, "encoder reference");
        delivery = delivery == null ? DeliveryOverrideConfig.INHERIT : delivery;
        if (!Set.of("stdout", "stderr").contains(stream)) {
            throw new IllegalArgumentException("stream must be stdout or stderr");
        }
        if (flushInterval.isNegative() || flushInterval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("flushInterval must be between 0s and 1m");
        }
    }

    public JsonStreamOutputConfig(
            String name,
            Level minimumLevel,
            String stream,
            Duration flushInterval,
            DeliveryOverrideConfig delivery) {
        this(name, minimumLevel, stream, flushInterval, null, delivery);
    }

    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }
}
