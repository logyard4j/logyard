package com.zsumz.logyard.api.spi.output;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.spi.encoding.EventEncoder;
import com.zsumz.logyard.api.spi.formatting.TextFormatter;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable resources supplied when a custom output instance is created.
 *
 * <p>{@link #formatter()} and {@link #encoder()} are {@code null} unless the
 * custom output explicitly references named definitions. The attributes and
 * components are detached runtime-owned values and must not be mutated.</p>
 *
 * @param outputName configured output name
 * @param resourceAttributes immutable service resource attributes
 * @param shutdownTimeout maximum graceful shutdown duration
 * @param formatter referenced formatter, or {@code null}
 * @param encoder referenced encoder, or {@code null}
 */
public record OutputProviderContext(
        String outputName,
        AttributeSet resourceAttributes,
        Duration shutdownTimeout,
        TextFormatter formatter,
        EventEncoder encoder) {
    /** Validates and normalizes output-scoped resources. */
    public OutputProviderContext {
        outputName = Objects.requireNonNull(outputName, "outputName").trim();
        if (!outputName.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid custom output name: " + outputName);
        }
        resourceAttributes = Objects.requireNonNull(resourceAttributes, "resourceAttributes");
        shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdown timeout must not be negative");
        }
    }
}
