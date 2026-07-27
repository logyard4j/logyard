package com.zsumz.logyard.runtime.assembly.output;

import com.zsumz.logyard.config.delivery.DeliveryConfig;
import com.zsumz.logyard.config.encoding.EncoderConfig;
import com.zsumz.logyard.config.formatting.FormatterConfig;
import com.zsumz.logyard.config.encoding.JsonProfileConfig;
import com.zsumz.logyard.config.output.OutputConfig;
import com.zsumz.logyard.config.runtime.ResourceConfig;
import com.zsumz.logyard.config.runtime.ServiceConfig;
import com.zsumz.logyard.config.theme.ThemeConfig;

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
