package com.zsumz.logyard.config;

import com.zsumz.logyard.api.Level;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Framed encoded file-output configuration. */
public record JsonFileOutputConfig(
        String name,
        Level minimumLevel,
        Path path,
        int bufferBytes,
        Duration flushInterval,
        boolean append,
        RotationConfig rotation,
        String encoder,
        DeliveryOverrideConfig delivery) implements OutputConfig {
    public JsonFileOutputConfig {
        name = ConfigNames.component(name, "output name");
        Objects.requireNonNull(minimumLevel, "minimumLevel");
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Objects.requireNonNull(flushInterval, "flushInterval");
        encoder = ConfigNames.optionalReference(encoder, "encoder reference");
        delivery = delivery == null ? DeliveryOverrideConfig.INHERIT : delivery;
        if (bufferBytes < 1_024 || bufferBytes > 16 * 1_024 * 1_024) {
            throw new IllegalArgumentException("bufferBytes must be between 1KiB and 16MiB");
        }
        if (flushInterval.isNegative() || flushInterval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("flushInterval must be between 0s and 1m");
        }
    }

    public JsonFileOutputConfig(
            String name,
            Level minimumLevel,
            Path path,
            int bufferBytes,
            Duration flushInterval,
            boolean append,
            RotationConfig rotation,
            DeliveryOverrideConfig delivery) {
        this(name, minimumLevel, path, bufferBytes, flushInterval, append, rotation, null, delivery);
    }
}
