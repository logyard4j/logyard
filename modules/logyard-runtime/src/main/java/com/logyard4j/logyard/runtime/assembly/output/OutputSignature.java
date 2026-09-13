package com.logyard4j.logyard.runtime.assembly.output;

import com.logyard4j.logyard.config.delivery.DeliveryConfig;
import com.logyard4j.logyard.config.encoding.EncoderConfig;
import com.logyard4j.logyard.config.formatting.FormatterConfig;
import com.logyard4j.logyard.config.encoding.JsonProfileConfig;
import com.logyard4j.logyard.config.output.OutputConfig;
import com.logyard4j.logyard.config.runtime.ResourceConfig;
import com.logyard4j.logyard.config.runtime.ServiceConfig;
import com.logyard4j.logyard.config.theme.ThemeConfig;

import java.time.Duration;
import java.util.Objects;

/** Immutable construction signature used to prove an output is safe to reuse. */
public record OutputSignature(
        OutputConfig output,
        DeliveryConfig delivery,
        ServiceConfig serviceIdentity,
        ResourceConfig resource,
        ThemeConfig theme,
        FormatterConfig formatter,
        EncoderConfig encoder,
        JsonProfileConfig jsonProfile,
        Duration shutdownTimeout) {
    public OutputSignature {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
    }
}
