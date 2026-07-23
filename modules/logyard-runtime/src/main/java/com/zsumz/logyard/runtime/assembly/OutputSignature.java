package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.config.DeliveryConfig;
import com.zsumz.logyard.config.EncoderConfig;
import com.zsumz.logyard.config.FormatterConfig;
import com.zsumz.logyard.config.JsonProfileConfig;
import com.zsumz.logyard.config.OutputConfig;
import com.zsumz.logyard.config.ResourceConfig;
import com.zsumz.logyard.config.ServiceConfig;
import com.zsumz.logyard.config.ThemeConfig;

import java.time.Duration;
import java.util.Objects;

/** Immutable construction signature used to prove an output is safe to reuse. */
record OutputSignature(
        OutputConfig output,
        DeliveryConfig delivery,
        ServiceConfig serviceIdentity,
        ResourceConfig resource,
        ThemeConfig theme,
        FormatterConfig formatter,
        EncoderConfig encoder,
        JsonProfileConfig jsonProfile,
        Duration shutdownTimeout) {
    OutputSignature {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
    }
}
