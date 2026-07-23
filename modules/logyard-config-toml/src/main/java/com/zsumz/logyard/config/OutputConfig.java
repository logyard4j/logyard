package com.zsumz.logyard.config;

import com.zsumz.logyard.api.Level;

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
