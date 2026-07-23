package com.zsumz.logyard.config.output;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.config.delivery.DeliveryOverrideConfig;

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
