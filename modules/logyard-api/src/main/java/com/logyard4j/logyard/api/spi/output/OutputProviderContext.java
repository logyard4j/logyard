package com.logyard4j.logyard.api.spi.output;

import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.spi.encoding.EventEncoder;
import com.logyard4j.logyard.api.spi.formatting.TextFormatter;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable resources supplied when a custom output instance is created.
 *
 * <p>{@link #formatter()} and {@link #encoder()} are {@code null} unless the
 * custom output explicitly references named definitions. The attributes and
 * components are detached runtime-owned values and must not be mutated.</p>
 *
 * @param outputName configured output name, 1–64 characters after trimming; starts with an ASCII letter
 *                   and contains only ASCII letters, digits, dots, underscores, or hyphens
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
    private static final int MAX_OUTPUT_NAME_CHARS = 64;

    /** Validates and normalizes output-scoped resources. */
    public OutputProviderContext {
        outputName = normalizeName(outputName);
        resourceAttributes = Objects.requireNonNull(resourceAttributes, "resourceAttributes");
        shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdown timeout must not be negative");
        }
    }

    private static String normalizeName(String name) {
        Objects.requireNonNull(name, "outputName");
        int start = 0;
        int end = name.length();
        while (start < end && name.charAt(start) <= ' ') start++;
        while (start < end && name.charAt(end - 1) <= ' ') end--;
        if (end - start > MAX_OUTPUT_NAME_CHARS) {
            throw new IllegalArgumentException(
                    "custom output name exceeds " + MAX_OUTPUT_NAME_CHARS + " characters after trimming");
        }
        String normalized = name.substring(start, end);
        if (!normalized.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("invalid custom output name: " + normalized);
        }
        return normalized;
    }
}
