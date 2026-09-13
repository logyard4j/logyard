package com.logyard4j.logyard.config.output;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.config.delivery.DeliveryOverrideConfig;

/** Common immutable configuration contract for a named output. */
public sealed interface OutputConfig permits
        ConsoleOutputConfig,
        JsonFileOutputConfig,
        JsonStreamOutputConfig,
        CustomOutputConfig {
    String name();

    Level minimumLevel();

    DeliveryOverrideConfig delivery();
}
