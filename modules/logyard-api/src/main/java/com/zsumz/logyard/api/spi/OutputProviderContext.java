package com.zsumz.logyard.api.spi;

import com.zsumz.logyard.api.event.AttributeSet;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable resources supplied when a custom output instance is created.
 *
 * <p>{@link #formatter()} and {@link #encoder()} are {@code null} unless the
 * custom output explicitly references named definitions. The attributes and
 * components are detached runtime-owned values and must not be mutated.</p>
 */
public record OutputProviderContext(
        String outputName,
        AttributeSet resourceAttributes,
        Duration shutdownTimeout,
        TextFormatter formatter,
        EventEncoder encoder) {
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
