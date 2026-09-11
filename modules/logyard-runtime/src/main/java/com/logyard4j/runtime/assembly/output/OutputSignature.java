package com.logyard4j.runtime.assembly.output;

import com.logyard4j.config.delivery.DeliveryConfig;
import com.logyard4j.config.encoding.EncoderConfig;
import com.logyard4j.config.formatting.FormatterConfig;
import com.logyard4j.config.encoding.JsonProfileConfig;
import com.logyard4j.config.output.OutputConfig;
import com.logyard4j.config.runtime.ResourceConfig;
import com.logyard4j.config.runtime.ServiceConfig;
import com.logyard4j.config.theme.ThemeConfig;

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
