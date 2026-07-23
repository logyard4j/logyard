package com.zsumz.logyard.runtime.assembly.output;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.config.output.CustomOutputConfig;
import com.zsumz.logyard.config.delivery.DeliveryConfig;
import com.zsumz.logyard.config.output.OutputConfig;
import com.zsumz.logyard.core.delivery.AsyncSink;
import com.zsumz.logyard.core.delivery.FilteringSink;
import com.zsumz.logyard.core.delivery.OverflowPolicy;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Objects;

/** Applies bounded delivery and minimum-level policies to a raw output sink. */
public final class DeliveryAssembler {
    private DeliveryAssembler() {
    }

    /**
     * Wraps a raw sink with its configured delivery policy.
     *
     * <p>Provider-backed outputs are always isolated behind an owned bounded worker and can never deliver on caller threads.</p>
     *
     * @param output output definition
     * @param raw raw transport sink
     * @param delivery effective delivery policy
     * @param shutdownTimeout runtime shutdown deadline
     * @return fully wrapped output sink
     */
    public static EventSink wrap(OutputConfig output, EventSink raw, DeliveryConfig delivery, Duration shutdownTimeout) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");

        EventSink delivered;
        if (output instanceof CustomOutputConfig) {
            delivered = new AsyncSink(output.name(), raw, delivery.capacity(), overflowPolicy(delivery), shutdownTimeout, false);
        } else if (delivery.asynchronous()) {
            delivered = new AsyncSink(output.name(), raw, delivery.capacity(), overflowPolicy(delivery), shutdownTimeout);
        } else {
            delivered = raw;
        }
        return new FilteringSink(output.minimumLevel(), delivered);
    }

    private static OverflowPolicy overflowPolicy(DeliveryConfig delivery) {
        EnumMap<Level, OverflowPolicy.Rule> rules = new EnumMap<>(Level.class);
        delivery.overflow().forEach((level, configured) -> rules.put(level, new OverflowPolicy.Rule(configured.action(), configured.after())));
        return new OverflowPolicy(rules);
    }
}
