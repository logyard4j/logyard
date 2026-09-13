package com.logyard4j.logyard.config.output;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.config.delivery.DeliveryOverrideConfig;
import com.logyard4j.logyard.config.validation.ConfigNames;
import java.util.Objects;
import java.util.Set;

/** Semantic pretty-console output configuration. */
public record ConsoleOutputConfig(
        String name,
        Level minimumLevel,
        String stream,
        ColorConfig color,
        ExceptionConfig exception,
        String formatter,
        DeliveryOverrideConfig delivery) implements OutputConfig {
    public ConsoleOutputConfig {
        name = ConfigNames.component(name, "output name");
        Objects.requireNonNull(minimumLevel, "minimumLevel");
        stream = requireText(stream, "stream").toLowerCase(java.util.Locale.ROOT);
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(exception, "exception");
        formatter = ConfigNames.optionalReference(formatter, "formatter reference");
        delivery = delivery == null ? DeliveryOverrideConfig.INHERIT : delivery;
        if (!Set.of("stdout", "stderr").contains(stream)) {
            throw new IllegalArgumentException("stream must be stdout or stderr");
        }
    }

    public ConsoleOutputConfig(
            String name,
            Level minimumLevel,
            String stream,
            ColorConfig color,
            ExceptionConfig exception,
            DeliveryOverrideConfig delivery) {
        this(name, minimumLevel, stream, color, exception, null, delivery);
    }

    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }
}
