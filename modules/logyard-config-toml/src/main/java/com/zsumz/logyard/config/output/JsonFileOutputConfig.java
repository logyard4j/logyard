package com.zsumz.logyard.config.output;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.config.delivery.DeliveryOverrideConfig;
import com.zsumz.logyard.config.validation.ConfigNames;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Framed encoded file-output configuration.
 *
 * <p>{@code fsync} opts the output into durable flushing: when it is set every completed flush also
 * forces the file channel before it is considered complete. It is off by default because forcing
 * costs a device round trip per flush.</p>
 */
public record JsonFileOutputConfig(
        String name,
        Level minimumLevel,
        Path path,
        int bufferBytes,
        Duration flushInterval,
        boolean append,
        boolean fsync,
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
            String encoder,
            DeliveryOverrideConfig delivery) {
        this(name, minimumLevel, path, bufferBytes, flushInterval, append, false, rotation, encoder, delivery);
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
        this(name, minimumLevel, path, bufferBytes, flushInterval, append, false, rotation, null, delivery);
    }
}
