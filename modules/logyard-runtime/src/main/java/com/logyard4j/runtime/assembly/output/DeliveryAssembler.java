package com.logyard4j.runtime.assembly.output;

import com.logyard4j.api.Level;
import com.logyard4j.api.failure.FailureIsolation;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.config.delivery.DeliveryConfig;
import com.logyard4j.config.output.CustomOutputConfig;
import com.logyard4j.config.output.OutputConfig;
import com.logyard4j.core.delivery.FilteringSink;
import com.logyard4j.core.delivery.async.AsyncSink;
import com.logyard4j.core.delivery.async.OverflowPolicy;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Objects;
import java.util.function.Function;

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
        return wrap(output, raw, delivery, shutdownTimeout, delivered -> new FilteringSink(output.minimumLevel(), delivered));
    }

    static EventSink wrap(
            OutputConfig output,
            EventSink raw,
            DeliveryConfig delivery,
            Duration shutdownTimeout,
            Function<EventSink, EventSink> finalDecorator) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        Objects.requireNonNull(finalDecorator, "finalDecorator");

        EventSink owned = raw;
        try {
            if (output instanceof CustomOutputConfig) {
                owned = new AsyncSink(output.name(), raw, delivery.capacity(), overflowPolicy(delivery), shutdownTimeout, false);
            } else if (delivery.asynchronous()) {
                owned = new AsyncSink(output.name(), raw, delivery.capacity(), overflowPolicy(delivery), shutdownTimeout);
            }
            return Objects.requireNonNull(finalDecorator.apply(owned), "delivery decorator returned null");
        } catch (RuntimeException | Error failure) {
            closeAfterConstructionFailure(owned, failure);
            FailureIsolation.prepareForRecovery(failure);
            throw failure;
        }
    }

    private static OverflowPolicy overflowPolicy(DeliveryConfig delivery) {
        EnumMap<Level, OverflowPolicy.Rule> rules = new EnumMap<>(Level.class);
        delivery.overflow().forEach((level, configured) -> rules.put(level, new OverflowPolicy.Rule(configured.action(), configured.after())));
        return new OverflowPolicy(rules);
    }

    private static void closeAfterConstructionFailure(EventSink owned, Throwable constructionFailure) {
        try {
            owned.close();
        } catch (Throwable cleanupFailure) {
            FailureIsolation.prepareForRecovery(cleanupFailure);
            if (cleanupFailure != constructionFailure) {
                constructionFailure.addSuppressed(cleanupFailure);
            }
        }
    }
}
